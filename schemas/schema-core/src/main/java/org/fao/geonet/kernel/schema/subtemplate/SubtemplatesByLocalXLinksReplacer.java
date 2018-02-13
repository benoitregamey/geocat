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

package org.fao.geonet.kernel.schema.subtemplate;

import org.apache.lucene.index.IndexReader;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SubtemplatesByLocalXLinksReplacer  {

    private Map<String, Replacer> replacersDict = new HashMap<>();
    private ManagersProxy managersProxy;
    private List<Namespace> namespaces;

    public SubtemplatesByLocalXLinksReplacer(List<Namespace> namespaces, ManagersProxy managersProxy) {
        this.managersProxy = managersProxy;
        this.namespaces = namespaces;
    }

    public static String FORMAT = "format";
    public static String CONTACT = "contact";
    public static String EXTENT = "extent";
    public static String KEYWORD = "keyword";

    public Element replaceSubtemplatesByLocalXLinks(Element dataXml,
                                                    String templatesToOperateOn) {

        String localXlinkUrlPrefix = "local://srv/api/registries/entries/";
        final String lang = computeMetadataLang(dataXml);
        final Set<String> localesAsHrefParam = getLocalesAsHrefParam(dataXml);
        final String localisedCharacterStringLanguageCode =
                String.format("#%S", managersProxy.getIso1LangCode(lang));

        IndexReader indexReader;
        try {
            indexReader = managersProxy.getIndexReader(null);
        } catch (IOException e) {
            throw new RuntimeException("IOException, SubtemplatesByLocalXLinksReplacer, unable to init index reader.");
        }

        try {
            Status status = Arrays.stream(templatesToOperateOn.split(";"))
                    .filter(replacersDict::containsKey)
                    .map(replacersDict::get)
                    .map(replacer -> replacer.replaceAll(dataXml,
                            localXlinkUrlPrefix,
                            indexReader,
                            localisedCharacterStringLanguageCode,
                            lang,
                            localesAsHrefParam))
                    .collect(Status.STATUS_COLLECTOR);

            if (status.isError()) {
                throw new RuntimeException(status.msg);
            } else {
                return dataXml;
            }
        } finally {
            try {
                managersProxy.releaseIndexReader(indexReader);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void addReplacer(Replacer replacer) {
        replacersDict.put(replacer.getAlias(), replacer);
    }

    private String computeMetadataLang(Element dataXml) {
        String lang = "eng";
        try {
            lang = Xml.selectElement(dataXml, ".//gmd:language/gmd:LanguageCode", namespaces).getAttributeValue("codeListValue");
        } catch (Exception e) {
            try {
                lang = Xml.selectElement(dataXml, ".//gmd:language/gco:CharacterString", namespaces).getText();
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
        return replaceToleredLanguageCodeByExpectedOnes(lang);
    }

    private Set<String> getLocalesAsHrefParam(Element dataXml) {
        try {
            Set<String> locales = Xml.selectNodes(dataXml, ".//gmd:locale/gmd:PT_Locale/gmd:languageCode/*")
                    .stream()
                    .map(element -> String.format("%s=%s", "lang", replaceToleredLanguageCodeByExpectedOnes(
                            ((Element) element).getAttribute("codeListValue").getValue())))
                    .collect(Collectors.toSet());
            return locales;
        } catch (JDOMException e) {
            return Collections.emptySet();
        }
    }

    private String replaceToleredLanguageCodeByExpectedOnes(String lang) {
        if ("deu".compareTo(lang) == 0) {
            return "ger";
        }

        if ("fra".compareTo(lang) == 0) {
            return "fre";
        }
        return lang;
    }
}
