/*
 * Copyright (C) 2001-2016 Food and Agriculture Organization of the
 * United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * and United Nations Environment Programme (UNEP)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 *
 * Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * Rome - Italy. email: geonetwork@osgeo.org
 */

package org.fao.geonet.kernel.unpublish;

import com.google.common.collect.Sets;
import jeeves.interfaces.Service;
import jeeves.server.ServiceConfig;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import jeeves.server.dispatchers.ServiceManager;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.fao.geonet.ApplicationContextHolder;
import org.fao.geonet.constants.Edit;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.*;
import org.fao.geonet.domain.geocat.PublishRecord;
import org.fao.geonet.exceptions.JeevesException;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.XmlSerializer;
import org.fao.geonet.kernel.search.DuplicateDocFilter;
import org.fao.geonet.kernel.search.ISearchManager;
import org.fao.geonet.kernel.search.IndexAndTaxonomy;
import org.fao.geonet.kernel.search.SearchManager;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.repository.MetadataRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.fao.geonet.repository.UserRepository;
import org.fao.geonet.repository.geocat.PublishRecordRepository;
import org.fao.geonet.repository.geocat.specification.PublishRecordSpecs;
import org.fao.geonet.repository.specification.MetadataSpecs;
import org.fao.geonet.repository.specification.OperationAllowedSpecs;
import org.fao.geonet.utils.Log;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.jdom.Text;
import org.jdom.filter.Filter;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.fao.geonet.repository.specification.OperationAllowedSpecs.hasMetadataId;
import static org.fao.geonet.repository.specification.OperationAllowedSpecs.isPublic;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.springframework.data.jpa.domain.Specifications.where;

public class UnpublishInvalidMetadataJob extends QuartzJobBean {
    public static final String UNPUBLISH_LOG = Geonet.GEONETWORK + ".unpublish";
    public static final Filter ReportFinder = new Filter() {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean matches(Object obj) {
            if (obj instanceof Element) {
                Element element = (Element) obj;
                String name = element.getName();
                if (name.equals("report") || name.equals("xsderrors")) {
                    return true;
                }
            }
            return false;
        }
    };
    public static final Filter ErrorFinder  = new Filter() {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean matches(Object obj) {
            if (obj instanceof Element) {
                Element element = (Element) obj;
                String name = element.getName();
                if (name.equals("error")) {
                    return true;
                } else if (name.equals("failed-assert")) {
                    return true;
                }
            }
            return false;
        }
    };
    @Autowired
    XmlSerializer xmlSerializer;
    @Autowired
    private ConfigurableApplicationContext context;
    @Autowired
    private ServiceManager serviceManager;
    @Autowired
    private OperationAllowedRepository operationAllowedRepository;
    static final String AUTOMATED_ENTITY = "Automated";

    AtomicBoolean running = new AtomicBoolean(false);

    public static Pair<String, String> failureReason(ServiceContext context, Element report) {

        @SuppressWarnings("unchecked")
        Iterator<Element> reports = report.getDescendants(ReportFinder);

        StringBuilder rules = new StringBuilder();
        StringBuilder failures = new StringBuilder();
        while (reports.hasNext()) {
            report = reports.next();
            if (report.getName().equals("xsderrors")) {
                processXsdError(report, rules, failures);
            } else {
                processSchematronError(report, rules, failures);
            }
        }

        return Pair.read(rules.toString(), failures.toString());
    }

    private static void processXsdError(Element report, StringBuilder rules, StringBuilder failures) {
        String reportType = "Xsd Error";
        @SuppressWarnings("unchecked")
        Iterator<Element> errors = report.getDescendants(ErrorFinder);
        if (errors.hasNext()) {
            rules.append("<div class=\"rule\">").append(reportType).append("</div>");

            while (errors.hasNext()) {
                failures.append("<div class=\"failure\">");
                Element error = errors.next();
                failures.append("</div><div class=\"xpath\">");
                failures.append(" XPATH of failure:");
                failures.append(error.getChildText("xpath", Edit.NAMESPACE));
                failures.append("</div><h4>Reason</h4><div class=\"reason\">");
                failures.append(error.getChildText("message", Edit.NAMESPACE));
                failures.append("</div>");
                failures.append("</div>");
            }
        }
    }

    private static void processSchematronError(Element report, StringBuilder rules, StringBuilder failures) {
        String reportType = report.getAttributeValue("rule", Edit.NAMESPACE);
        reportType = reportType == null ? "No name for rule" : reportType;

        boolean isMandatory = SchematronRequirement.REQUIRED.name().equals(report.getAttributeValue("required", Edit.NAMESPACE));

        if (isMandatory) {
            @SuppressWarnings("unchecked")
            Iterator<Element> errors = report.getDescendants(ErrorFinder);

            if (errors.hasNext()) {
                rules.append("<div class=\"rule\">").append(reportType).append("</div>");

                while (errors.hasNext()) {
                    failures.append("<div class=\"failure\">\n");

                    Element error = errors.next();

                    Element text = error.getChild("text", Geonet.Namespaces.SVRL);
                    if (text != null) {
                        failures.append("  <div class=\"test\">Schematron Test: ");
                        failures.append(error.getAttributeValue("test"));
                        failures.append("  </div>\n  <div class=\"xpath\">");
                        failures.append("XPATH of failure: ");
                        failures.append(error.getAttributeValue("location"));
                        failures.append("  </div>\n<h4>Reason</h4><div class=\"reason\">");
                        List children = text.getContent();

                        for (Object child : children) {
                            if (child instanceof Element) {
                                failures.append(Xml.getString((Element) child));
                            } else if (child instanceof Text) {
                                failures.append(((Text) child).getText());
                            }
                        }
                        failures.append("  </div>\n");
                    } else {
                        failures.append("unknown reason");
                    }
                    failures.append("</div>\n");
                }
            }
        }
    }


    @Override
    protected void executeInternal(JobExecutionContext jobContext) throws JobExecutionException {
        ApplicationContextHolder.set(this.context);
        final UserRepository userRepository = context.getBean(UserRepository.class);

        int id = 1;

        try {
            final List<User> allByProfile = userRepository.findAllByProfile(Profile.Administrator);
            if (!allByProfile.isEmpty()) {
                id = allByProfile.get(0).getId();
            }
        } catch (Throwable e) {
            Log.error(Geonet.DATA_MANAGER, "Error during unpublish", e);
        }

        ServiceContext serviceContext = serviceManager.createServiceContext("unpublishMetadata", context);
        serviceContext.setAsThreadLocal();

        final UserSession userSession = new UserSession();
        User user = new User();
        user.setId(id);
        user.setProfile(Profile.Administrator);
        user.setUsername("admin");

        userSession.loginAs(user);
        serviceContext.setUserSession(userSession);
        try {
            performJob(serviceContext);
        } catch (Exception e) {
            Log.error(Geonet.GEONETWORK, "Error running " + UnpublishInvalidMetadataJob.class.getSimpleName(), e);
        }

    }

    // --------------------------------------------------------------------------------

    private void performJob(ServiceContext serviceContext) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Unpublish Job is already running");
        }
        try {
            long startTime = System.currentTimeMillis();
            Log.info(UNPUBLISH_LOG, "Starting Unpublish Invalid Metadata Job");
            Integer keepDuration = serviceContext.getBean(SettingManager.class).getValueAsInt("system/metadata/publish_tracking_duration");
            if (keepDuration == null) {
                keepDuration = 100;
            }


            List<Metadata> metadataToTest;

            // clean up expired changes
            final PublishRecordRepository publishRepository = serviceContext.getBean(PublishRecordRepository.class);
            publishRepository.deleteAll(PublishRecordSpecs.daysOldOrOlder(keepDuration));

            metadataToTest = lookUpMetadataIds(serviceContext.getBean(MetadataRepository.class));

            DataManager dataManager = serviceContext.getBean(DataManager.class);
            for (Metadata metadataRecord : metadataToTest) {
                ApplicationContextHolder.set(serviceContext.getApplicationContext());
                serviceContext.setAsThreadLocal();
                final String id = "" + metadataRecord.getId();
                try {
                    checkIfNeedsUnpublishingAndSavePublishedRecord(serviceContext, metadataRecord, dataManager);
                    dataManager.indexMetadata(id, false, null);
                } catch (Exception e) {
                    String error = Xml.getString(JeevesException.toElement(e));
                    Log.error(UNPUBLISH_LOG, "Error during Validation/Unpublish process of metadata " + id + ".  Exception: " + error);
                }

                serviceContext.getBean(DataManager.class).flush();
            }

            long timeSec = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime);
            Log.info(UNPUBLISH_LOG, "Finishing Unpublish Invalid Metadata Job.  Job took:  " + timeSec + " sec");

            indexNonValidatedMetadata(serviceContext, dataManager);
        } finally {
            running.set(false);
        }
    }

    private void indexNonValidatedMetadata(ServiceContext serviceContext, DataManager dataManager) throws Exception {
        Log.info(UNPUBLISH_LOG, "Start Unpublish Invalid Metadata Job.");
        long startTime = System.currentTimeMillis();
        SearchManager searchManager = serviceContext.getBean(SearchManager.class);
        Set<String> ids = Sets.newHashSet();
        try (IndexAndTaxonomy iat = searchManager.getNewIndexReader(null)) {
            IndexSearcher searcher = new IndexSearcher(iat.indexReader);
            BooleanQuery query = new BooleanQuery();
            query.add(new BooleanClause(new TermQuery(new Term("_valid", "-1")), BooleanClause.Occur.MUST));
            query.add(new BooleanClause(new TermQuery(new Term(Geonet.IndexFieldNames.IS_HARVESTED, "n")), BooleanClause.Occur.MUST));
            query.add(new BooleanClause(new TermQuery(new Term(Geonet.IndexFieldNames.IS_TEMPLATE, "n")), BooleanClause.Occur.MUST));
            query.add(new BooleanClause(new TermQuery(new Term(Geonet.IndexFieldNames.SCHEMA, "iso19139.che")), BooleanClause.Occur.MUST));
            TopDocs topDocs = searcher.search(query, new DuplicateDocFilter(query), Integer.MAX_VALUE);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                ids.add(searcher.doc(scoreDoc.doc).get(Geonet.IndexFieldNames.ID));
            }
        }

        for (String mdId : ids) {
            dataManager.indexMetadata(mdId, false, null);
        }
        long timeSec = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime);
        Log.info(UNPUBLISH_LOG, "Finishing Indexing metadata that have not been validated in for index.  "
                                + "Job took:  " + timeSec + " sec");
    }

    /**
	 * Check if if the given metadata needs to be unpublished (because of
	 * invalidation), then saves the publish record state into database and
	 * unpublishes it if needed.
	 *
	 * @param context
	 *            the application context
	 * @param metadataRecord
	 *            the metadata to check / unpublish
	 * @param dataManager
	 *            the datamanager object (used to check validity)
	 *
	 * @return true if the MD has been invalidated (was published, but has been
	 *         unpublished), false otherwise (if already unpublished, or if
	 *         published but recognized valid).
	 *
	 * @throws Exception
	 */
    private boolean checkIfNeedsUnpublishingAndSavePublishedRecord(ServiceContext context, Metadata metadataRecord,
                                                                   DataManager dataManager) throws Exception {
        String id = "" + metadataRecord.getId();
        Element md   = xmlSerializer.select(context, String.valueOf(metadataRecord.getId()));
        String schema = metadataRecord.getDataInfo().getSchemaId();
        PublishRecord todayRecord;
        boolean published = isPublished(id, context);

        if (published) {
            Element report = dataManager.doValidate(context.getUserSession(), schema, id, md, "eng", false).one();

            Pair<String, String> failureReport = failureReason(context, report);
            String failureRule = failureReport.one();
            String failureReasons = failureReport.two();
            if (!failureRule.isEmpty()) {
                todayRecord = new PublishRecord();
                todayRecord.setChangedate(new Date());
                todayRecord.setChangetime(new Date());
                todayRecord.setFailurereasons(failureReasons);
                todayRecord.setFailurerule(failureRule);
                todayRecord.setUuid(metadataRecord.getUuid());
                todayRecord.setEntity(AUTOMATED_ENTITY);
                todayRecord.setPublished(false);
                todayRecord.setGroupOwner(metadataRecord.getSourceInfo().getGroupOwner());
                todayRecord.setSource(metadataRecord.getSourceInfo().getSourceId());
                todayRecord.setValidated(PublishRecord.Validity.fromBoolean(false));
                context.getBean(PublishRecordRepository.class).save(todayRecord);

                final Specifications<OperationAllowed> publicOps = Specifications.where(isPublic(ReservedOperation.view)).
                        or(isPublic(ReservedOperation.download)).
                        or(isPublic(ReservedOperation.editing)).
                        or(isPublic(ReservedOperation.featured)).
                        or(isPublic(ReservedOperation.dynamic));
                operationAllowedRepository.deleteAll(Specifications.where(hasMetadataId(metadataRecord.getId())).and(publicOps));
                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether the given metadata is published or not, i.e. if all reserved groups have the view operation set.
     *
     * @param id the metadata Identifier
     * @param context the application context
     * @return true if the metadata is published, false otherwise.
     *
     * @throws SQLException
     */
    public static boolean isPublished(String id, ServiceContext context) throws SQLException {
        final OperationAllowedRepository allowedRepository = context.getBean(OperationAllowedRepository.class);

        final Specifications<OperationAllowed> idAndPublishedSpec = where(isPublic(ReservedOperation.view)).and
                (OperationAllowedSpecs.hasMetadataId(id));
        return allowedRepository.count(idAndPublishedSpec) > 0;
    }

    /**
     * Creates a list of metadata needed to be schematron checked, i.e.:
     *
     *  - not harvested
     *  - of type Metadata (no template nor subtemplate)
     *  - of schema ISO19139.che
     *
     * @param repo the MetadataRepository JPA
     * @return a list of metadata objects to check
     *
     * @throws SQLException
     */
    private List<Metadata> lookUpMetadataIds(MetadataRepository repo) throws SQLException {
        final Specification<Metadata> notHarvested = MetadataSpecs.isHarvested(false);
        Specification<Metadata> isMetadata = MetadataSpecs.isType(MetadataType.METADATA);
        Specification<Metadata> isCHEMetadata = MetadataSpecs.hasSchemaId("iso19139.che");
        return repo.findAll(where(notHarvested).and(isMetadata).and(isCHEMetadata));
    }

    /**
     * Gets a list of published states for the MDs, between 2 dates given as argument.
     *
     * @param context a ServiceContext object, used to get a hook onto the JPA repositories
     * @param startOffset
     * @param endOffset
     * @return the list of published states (see the publish_tracking table in db)
     *
     * @throws Exception
     */
    static List<PublishRecord> values(ServiceContext context, int startOffset, int endOffset) throws Exception {
        final Specification<PublishRecord> daysOldOrNewer = PublishRecordSpecs.daysOldOrNewer(startOffset);
        final Specification<PublishRecord> daysOldOrOlder = PublishRecordSpecs.daysOldOrOlder(endOffset);

        return context.getBean(PublishRecordRepository.class).findAll(where(daysOldOrNewer).and(daysOldOrOlder));
    }
}
