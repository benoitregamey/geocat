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

package org.fao.geonet.repository.specification;

import org.fao.geonet.domain.Link;
import org.fao.geonet.domain.Link_;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.MetadataLink;
import org.fao.geonet.domain.MetadataLink_;
import org.fao.geonet.domain.MetadataSourceInfo_;
import org.fao.geonet.domain.Metadata_;
import org.fao.geonet.domain.OperationAllowed;
import org.fao.geonet.domain.OperationAllowedId_;
import org.fao.geonet.domain.OperationAllowed_;
import org.fao.geonet.domain.ReservedOperation;
import org.springframework.data.jpa.domain.Specification;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;

public class LinkSpecs {
    private LinkSpecs() {
    }

    public static Specification<Link> filter(String urlPartToContain,
                                             Integer state,
                                             String associatedRecord,
                                             Integer[] groupPublishedIds,
                                             Integer[] groupOwnerIds,
                                             boolean publishedOrOwnerFilter) {

        return new Specification<Link>() {
            @Override
            public Predicate toPredicate(Root<Link> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
                query.distinct(true);
                List<Predicate> predicates = new ArrayList<>();
                Predicate groupOwnerPredicate = null;
                Predicate groupPublishedPredicate = null;

                Join<Link, MetadataLink> metadataJoin;
                if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                    metadataJoin = (Join<Link, MetadataLink>) root.fetch(Link_.records, JoinType.LEFT);
                } else {
                    metadataJoin = root.join(Link_.records, JoinType.LEFT);
                };

                if (state != null) {
                    predicates.add(
                        cb.equal(
                            root.get(Link_.lastState),
                            state));
                }

                if (urlPartToContain != null) {
                    predicates.add(
                        cb.like(
                            root.get(Link_.url),
                            cb.literal(String.format("%%%s%%", urlPartToContain))));
                }

                if (associatedRecord != null) {
                    predicates.add(
                        cb.like(
                            metadataJoin.get(MetadataLink_.metadataUuid),
                            cb.literal(String.format("%%%s%%", associatedRecord))));
                }

                if (groupPublishedIds != null) {
                    Subquery<Integer> subquery = query.subquery(Integer.class);
                    Root<OperationAllowed> fromOperationAllowed = subquery.from(OperationAllowed.class);
                    Predicate publishedToIndicatedGroup =
                        fromOperationAllowed.get(OperationAllowed_.id).get(OperationAllowedId_.groupId).in(groupPublishedIds);
                    Predicate operationTypeView = cb.equal(
                        fromOperationAllowed.get(OperationAllowed_.id).get(OperationAllowedId_.operationId),
                        cb.literal(ReservedOperation.view.getId()));
                    subquery.where(cb.and(publishedToIndicatedGroup, operationTypeView));

                    Path<Integer> opAllowedMetadataId = fromOperationAllowed.get(OperationAllowed_.id).get(OperationAllowedId_.metadataId);
                    subquery.select(opAllowedMetadataId);

                    groupPublishedPredicate = metadataJoin.get(MetadataLink_.metadataId).in(subquery);
                }

                 if (groupOwnerIds != null) {
                    Subquery<Integer> subquery = query.subquery(Integer.class);
                    Root<Metadata> fromMetadata = subquery.from(Metadata.class);
                    subquery.select(fromMetadata.get(Metadata_.id));
                    Path<Integer> metadataOwner = fromMetadata.get(Metadata_.sourceInfo).get(MetadataSourceInfo_.groupOwner);
                    subquery.where(metadataOwner.in(groupOwnerIds));

                    groupOwnerPredicate = metadataJoin.get(MetadataLink_.metadataId).in(subquery);
                }

                 if (publishedOrOwnerFilter && groupOwnerPredicate != null && groupPublishedPredicate != null) {
                     Predicate ownerOrPublished = cb.or(groupPublishedPredicate, groupOwnerPredicate);
                     predicates.add(ownerOrPublished);
                 } else {
                     if (groupOwnerPredicate != null) {
                         predicates.add(groupOwnerPredicate);
                     }
                     if (groupPublishedPredicate != null) {
                         predicates.add(groupPublishedPredicate);
                     }
                 }

                return cb.and(predicates.toArray(new Predicate[]{}));
            }
        };
    }
}
