/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.model.validation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.plugin.errors.ValidationErrorCode;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerAccessTypeDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerEnumDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerEnumElementDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerPolicyConditionDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerResourceDef;
import org.apache.ranger.plugin.model.RangerServiceDef.RangerServiceConfigDef;
import org.apache.ranger.plugin.store.ServiceStore;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class RangerServiceDefValidator extends RangerValidator {

	private static final Log LOG = LogFactory.getLog(RangerServiceDefValidator.class);

	public RangerServiceDefValidator(ServiceStore store) {
		super(store);
	}

	public void validate(final RangerServiceDef serviceDef, final Action action) throws Exception {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.validate(%s, %s)", serviceDef, action));
		}

		List<ValidationFailureDetails> failures = new ArrayList<ValidationFailureDetails>();
		boolean valid = isValid(serviceDef, action, failures);
		String message = "";
		try {
			if (!valid) {
				message = serializeFailures(failures);
				throw new Exception(message);
			}
		} finally {
			if(LOG.isDebugEnabled()) {
				LOG.debug(String.format("<== RangerServiceDefValidator.validate(%s, %s): %s, reason[%s]", serviceDef, action, valid, message));
			}
		}
	}

	boolean isValid(final Long id, final Action action, final List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerServiceDefValidator.isValid(" + id + ")");
		}

		boolean valid = true;
		if (action != Action.DELETE) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_UNSUPPORTED_ACTION;
			failures.add(new ValidationFailureDetailsBuilder()
					.isAnInternalError()
					.errorCode(error.getErrorCode())
					.becauseOf(error.getMessage(action))
					.build());
			valid = false;
		} else if (id == null) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_MISSING_FIELD;
			failures.add(new ValidationFailureDetailsBuilder()
				.field("id")
				.isMissing()
				.errorCode(error.getErrorCode())
				.becauseOf(error.getMessage("id"))
				.build());
			valid = false;
		} else if (getServiceDef(id) == null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("No service found for id[" + id + "]! ok!");
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerServiceDefValidator.isValid(" + id + "): " + valid);
		}
		return valid;
	}
	
	boolean isValid(final RangerServiceDef serviceDef, final Action action, final List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerServiceDefValidator.isValid(" + serviceDef + ")");
		}
		
		if (!(action == Action.CREATE || action == Action.UPDATE)) {
			throw new IllegalArgumentException("isValid(RangerServiceDef, ...) is only supported for CREATE/UPDATE");
		}
		boolean valid = true;
		if (serviceDef == null) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_NULL_SERVICE_DEF_OBJECT;
			failures.add(new ValidationFailureDetailsBuilder()
				.field("service def")
				.isMissing()
				.errorCode(error.getErrorCode())
				.becauseOf(error.getMessage(action))
				.build());
			valid = false;
		} else {
			Long id = serviceDef.getId();
			valid = isValidServiceDefId(id, action, failures) && valid;
			valid = isValidServiceDefName(serviceDef.getName(), id, action, failures) && valid;
			valid = isValidAccessTypes(serviceDef.getAccessTypes(), failures) && valid;
			if (isValidResources(serviceDef, failures)) {
				// Semantic check of resource graph can only be done if resources are "syntactically" valid
				valid = isValidResourceGraph(serviceDef, failures) && valid;
			} else {
				valid = false;
			}
			List<RangerEnumDef> enumDefs = serviceDef.getEnums();
			if (isValidEnums(enumDefs, failures)) {
				// config def validation requires valid enums
				valid = isValidConfigs(serviceDef.getConfigs(), enumDefs, failures) && valid;
			} else {
				valid = false;
			}
			valid = isValidPolicyConditions(serviceDef.getPolicyConditions(), failures) && valid;
		}
		
		if(LOG.isDebugEnabled()) {
			LOG.debug("<== RangerServiceDefValidator.isValid(" + serviceDef + "): " + valid);
		}
		return valid;
	}

	boolean isValidServiceDefId(Long id, final Action action, final List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidServiceDefId(%s, %s, %s)", id, action, failures));
		}
		boolean valid = true;

		if (action == Action.UPDATE) { // id is ignored for CREATE
			if (id == null) {
				ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_EMPTY_SERVICE_DEF_ID;
				failures.add(new ValidationFailureDetailsBuilder()
					.field("id")
					.isMissing()
					.errorCode(error.getErrorCode())
					.becauseOf(error.getMessage())
					.build());
				valid = false;
			} else if (getServiceDef(id) == null) {
				ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_INVALID_SERVICE_DEF_ID;
				failures.add(new ValidationFailureDetailsBuilder()
					.field("id")
					.isSemanticallyIncorrect()
					.errorCode(error.getErrorCode())
					.becauseOf(error.getMessage(id))
					.build());
				valid = false;
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidServiceDefId(%s, %s, %s): %s", id, action, failures, valid));
		}
		return valid;
	}
	
	boolean isValidServiceDefName(String name, Long id, final Action action, final List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidServiceDefName(%s, %s, %s, %s)", name, id, action, failures));
		}
		boolean valid = true;

		if (StringUtils.isBlank(name)) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_INVALID_SERVICE_DEF_NAME;
			failures.add(new ValidationFailureDetailsBuilder()
				.field("name")
				.isMissing()
				.errorCode(error.getErrorCode())
				.becauseOf(error.getMessage(name))
				.build());
			valid = false;
		} else {
			RangerServiceDef otherServiceDef = getServiceDef(name);
			if (otherServiceDef != null && action == Action.CREATE) {
				ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_SERVICE_DEF_NAME_CONFICT;
				failures.add(new ValidationFailureDetailsBuilder()
					.field("name")
					.isSemanticallyIncorrect()
					.errorCode(error.getErrorCode())
					.becauseOf(error.getMessage(name))
					.build());
				valid = false;
			} else if (otherServiceDef != null && !Objects.equals(id, otherServiceDef.getId())) {
				ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_ID_NAME_CONFLICT;
				failures.add(new ValidationFailureDetailsBuilder()
					.field("id/name")
					.isSemanticallyIncorrect()
					.errorCode(error.getErrorCode())
					.becauseOf(error.getMessage(name, otherServiceDef.getId()))
					.build());
				valid = false;
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidServiceDefName(%s, %s, %s, %s): %s", name, id, action, failures, valid));
		}
		return valid;
	}
	
	boolean isValidAccessTypes(final List<RangerAccessTypeDef> accessTypeDefs, final List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidAccessTypes(%s, %s)", accessTypeDefs, failures));
		}
		
		boolean valid = true;
		if (CollectionUtils.isEmpty(accessTypeDefs)) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_MISSING_FIELD;
			failures.add(new ValidationFailureDetailsBuilder()
				.field("access types")
				.isMissing()
				.errorCode(error.getErrorCode())
				.becauseOf(error.getMessage("access types"))
				.build());
			valid = false;
		} else {
			List<RangerAccessTypeDef> defsWithImpliedGrants = new ArrayList<RangerAccessTypeDef>();
			Set<String> accessNames = new HashSet<String>();
			Set<Long> ids = new HashSet<Long>();
			for (RangerAccessTypeDef def : accessTypeDefs) {
				String name = def.getName();
				valid = isUnique(name, accessNames, "access type name", "access types", failures) && valid;
				valid = isUnique(def.getItemId(), ids, "access type itemId", "access types", failures) && valid;
				if (CollectionUtils.isNotEmpty(def.getImpliedGrants())) {
					defsWithImpliedGrants.add(def);
				}
			}
			// validate implied grants
			for (RangerAccessTypeDef def : defsWithImpliedGrants) {
				Collection<String> impliedGrants = getImpliedGrants(def);
				Set<String> unknownAccessTypes = Sets.difference(Sets.newHashSet(impliedGrants), accessNames);
				if (!unknownAccessTypes.isEmpty()) {
					ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_IMPLIED_GRANT_UNKNOWN_ACCESS_TYPE;
					failures.add(new ValidationFailureDetailsBuilder()
						.field("implied grants")
						.subField(unknownAccessTypes.iterator().next())  // we return just on item here.  Message has all unknow items
						.isSemanticallyIncorrect()
						.errorCode(error.getErrorCode())
						.becauseOf(error.getMessage(impliedGrants, unknownAccessTypes))
						.build());
					valid = false;
				}
				// implied grant should not imply itself!
				String name = def.getName(); // note: this name could be null/blank/empty!
				if (impliedGrants.contains(name)) {
					ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_IMPLIED_GRANT_IMPLIES_ITSELF;
					failures.add(new ValidationFailureDetailsBuilder()
						.field("implied grants")
						.subField(name)
						.isSemanticallyIncorrect()
						.errorCode(error.getErrorCode())
						.becauseOf(error.getMessage(impliedGrants, name))
						.build());
					valid = false;
				}
			}
		}
		
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidAccessTypes(%s, %s): %s", accessTypeDefs, failures, valid));
		}
		return valid;
	}

	boolean isValidPolicyConditions(List<RangerPolicyConditionDef> policyConditions, List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidPolicyConditions(%s, %s)", policyConditions, failures));
		}
		boolean valid = true;

		if (CollectionUtils.isEmpty(policyConditions)) {
			LOG.debug("Configs collection was null/empty! ok");
		} else {
			Set<Long> ids = new HashSet<Long>();
			Set<String> names = new HashSet<String>();
			for (RangerPolicyConditionDef conditionDef : policyConditions) {
				valid = isUnique(conditionDef.getItemId(), ids, "policy condition def itemId", "policy condition defs", failures) && valid;
				String name = conditionDef.getName();
				valid = isUnique(name, names, "policy condition def name", "policy condition defs", failures) && valid;
				if (StringUtils.isBlank(conditionDef.getEvaluator())) {
					ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_POLICY_CONDITION_NULL_EVALUATOR;
					failures.add(new ValidationFailureDetailsBuilder()
						.field("policy condition def evaluator")
						.subField(name)
						.isMissing()
						.errorCode(error.getErrorCode())
						.becauseOf(error.getMessage(name))
						.build());
					valid = false;
				}
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidPolicyConditions(%s, %s): %s", policyConditions, failures, valid));
		}
		return valid;
	}

	boolean isValidConfigs(List<RangerServiceConfigDef> configs, List<RangerEnumDef> enumDefs, List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidConfigs(%s, %s, %s)", configs, enumDefs, failures));
		}
		boolean valid = true;

		if (CollectionUtils.isEmpty(configs)) {
			LOG.debug("Configs collection was null/empty! ok");
		} else {
			Set<Long> ids = new HashSet<Long>(configs.size());
			Set<String> names = new HashSet<String>(configs.size());
			for (RangerServiceConfigDef aConfig : configs) {
				valid = isUnique(aConfig.getItemId(), ids, "config def itemId", "config defs", failures) && valid;
				String configName = aConfig.getName();
				valid = isUnique(configName, names, "config def name", "config defs", failures) && valid;
				String type = aConfig.getType();
				valid = isValidConfigType(type, configName, failures) && valid;
				if ("enum".equals(type)) {
					valid = isValidConfigOfEnumType(aConfig, enumDefs, failures) && valid;
				}
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidConfigs(%s, %s, %s): %s", configs, enumDefs, failures, valid));
		}
		return valid;
	}
	
	boolean isValidConfigOfEnumType(RangerServiceConfigDef configDef, List<RangerEnumDef> enumDefs, List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidConfigOfEnumType(%s, %s, %s)", configDef, enumDefs, failures));
		}
		boolean valid = true;

		if (!"enum".equals(configDef.getType())) {
			LOG.debug("ConfigDef wasn't of enum type!");
		} else {
			Map<String, RangerEnumDef> enumDefsMap = getEnumDefMap(enumDefs);
			Set<String> enumTypes = enumDefsMap.keySet();
			String subType = configDef.getSubType();
			String configName = configDef.getName();
			
			if (!enumTypes.contains(subType)) {
				ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_CONFIG_DEF_UNKNOWN_ENUM;
				failures.add(new ValidationFailureDetailsBuilder()
					.field("config def subtype")
					.subField(configName)
					.isSemanticallyIncorrect()
					.errorCode(error.getErrorCode())
					.becauseOf(error.getMessage(subType, configName, enumTypes))
					.build());
				valid = false;
			} else {
				// default value check is possible only if sub-type is correctly configured
				String defaultValue = configDef.getDefaultValue();
				if (StringUtils.isNotBlank(defaultValue)) {
					RangerEnumDef enumDef = enumDefsMap.get(subType);
					Set<String> enumValues = getEnumValues(enumDef);
					if (!enumValues.contains(defaultValue)) {
						ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_CONFIG_DEF_UNKNOWN_ENUM_VALUE;
						failures.add(new ValidationFailureDetailsBuilder()
								.field("config def default value")
								.subField(configName)
								.isSemanticallyIncorrect()
								.errorCode(error.getErrorCode())
								.becauseOf(error.getMessage(defaultValue, configName, enumValues, subType))
								.build());
						valid = false;
					}
				}
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidConfigOfEnumType(%s, %s, %s): %s", configDef, enumDefs, failures, valid));
		}
		return valid;
	}
	
	boolean isValidConfigType(String type, String configName, List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidConfigType(%s, %s, %s)", type, configName, failures));
		}
		boolean valid = true;

		Set<String> validTypes = ImmutableSet.of("bool", "enum", "int", "string", "password", "path");
		if (StringUtils.isBlank(type)) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_CONFIG_DEF_MISSING_TYPE;
			failures.add(new ValidationFailureDetailsBuilder()
				.field("config def type")
				.subField(configName)
				.isMissing()
				.errorCode(error.getErrorCode())
				.becauseOf(error.getMessage(configName))
				.build());
			valid = false;
		} else if (!validTypes.contains(type)) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_CONFIG_DEF_INVALID_TYPE;
			failures.add(new ValidationFailureDetailsBuilder()
				.field("config def type")
				.subField(configName)
				.isSemanticallyIncorrect()
				.errorCode(error.getErrorCode())
				.becauseOf(error.getMessage(type, configName, validTypes))
				.build());
			valid = false;
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidConfigType(%s, %s, %s): %s", type, configName, failures, valid));
		}
		return valid;
	}

	boolean isValidResources(RangerServiceDef serviceDef, List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidResources(%s, %s)", serviceDef, failures));
		}
		boolean valid = true;

		List<RangerResourceDef> resources = serviceDef.getResources();
		if (CollectionUtils.isEmpty(resources)) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_MISSING_FIELD;
			failures.add(new ValidationFailureDetailsBuilder()
					.field("resources")
					.isMissing()
					.errorCode(error.getErrorCode())
					.becauseOf(error.getMessage("resources"))
					.build());
			valid = false;
		} else {
			Set<String> names = new HashSet<String>(resources.size());
			Set<Long> ids = new HashSet<Long>(resources.size());
			for (RangerResourceDef resource : resources) {
				/*
				 * While id is the natural key, name is a surrogate key.  At several places code expects resource name to be unique within a service.
				 */
				valid = isUnique(resource.getName(), names, "resource name", "resources", failures) && valid;
				valid = isUnique(resource.getItemId(), ids, "resource itemId", "resources", failures) && valid;
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidResources(%s, %s): %s", serviceDef, failures, valid));
		}
		return valid;
	}
	
	boolean isValidResourceGraph(RangerServiceDef serviceDef, List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidResourceGraph(%s, %s)", serviceDef, failures));
		}
		boolean valid = true;
		// We don't want this helper to get into the cache or to use what is in the cache!!
		RangerServiceDefHelper defHelper = _factory.createServiceDefHelper(serviceDef, false);
		if (!defHelper.isResourceGraphValid()) {
			ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_RESOURCE_GRAPH_INVALID;
			failures.add(new ValidationFailureDetailsBuilder()
				.field("resource graph")
				.isSemanticallyIncorrect()
				.errorCode(error.getErrorCode())
				.becauseOf(error.getMessage())
				.build());
			valid = false;
		}
		// resource level should be unique within a hierarchy
		for(int policyType : RangerPolicy.POLICY_TYPES) {
			Set<List<RangerResourceDef>> hierarchies = defHelper.getResourceHierarchies(policyType);
			for (List<RangerResourceDef> aHierarchy : hierarchies) {
				Set<Integer> levels = new HashSet<Integer>(aHierarchy.size());
				for (RangerResourceDef resourceDef : aHierarchy) {
					valid = isUnique(resourceDef.getLevel(), levels, "resource level", "resources", failures) && valid;
				}
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidResourceGraph(%s, %s): %s", serviceDef, failures, valid));
		}
		return valid;
	}
	
	boolean isValidEnums(List<RangerEnumDef> enumDefs, List<ValidationFailureDetails> failures) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidEnums(%s, %s)", enumDefs, failures));
		}
		
		boolean valid = true;
		if (CollectionUtils.isEmpty(enumDefs)) {
			LOG.debug("enum def collection passed in was null/empty. Ok.");
		} else {
			Set<String> names = new HashSet<String>();
			Set<Long> ids = new HashSet<Long>();
			for (RangerEnumDef enumDef : enumDefs) {
				if (enumDef == null) {
					ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_ENUM_DEF_NULL_OBJECT;
					failures.add(new ValidationFailureDetailsBuilder()
						.field("enum def")
						.isMissing()
						.errorCode(error.getErrorCode())
						.becauseOf(error.getMessage())
						.build());
					valid = false;
				} else {
					// enum-names and ids must non-blank and be unique to a service definition
					String enumName = enumDef.getName();
					valid = isUnique(enumName, names, "enum def name", "enum defs", failures) && valid;
					valid = isUnique(enumDef.getItemId(), ids, "enum def itemId", "enum defs", failures) && valid;		
					// enum must contain at least one valid value and those values should be non-blank and distinct
					if (CollectionUtils.isEmpty(enumDef.getElements())) {
						ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_ENUM_DEF_NO_VALUES;
						failures.add(new ValidationFailureDetailsBuilder()
							.field("enum values")
							.subField(enumName)
							.isMissing()
							.errorCode(error.getErrorCode())
							.becauseOf(error.getMessage(enumName))
							.build());
						valid = false;
					} else {
						valid = isValidEnumElements(enumDef.getElements(), failures, enumName) && valid;
						// default index should be valid
						int defaultIndex = getEnumDefaultIndex(enumDef);
						if (defaultIndex < 0 || defaultIndex >= enumDef.getElements().size()) { // max index is one less than the size of the elements list
							ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_ENUM_DEF_INVALID_DEFAULT_INDEX;
							failures.add(new ValidationFailureDetailsBuilder()
								.field("enum default index")
								.subField(enumName)
								.isSemanticallyIncorrect()
								.errorCode(error.getErrorCode())
								.becauseOf(error.getMessage(defaultIndex, enumName))
								.build());
							valid = false;
						}
					}
				}
			}
		}
		
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidEnums(%s, %s): %s", enumDefs, failures, valid));
		}
		return valid;
	}

	boolean isValidEnumElements(List<RangerEnumElementDef> enumElementsDefs, List<ValidationFailureDetails> failures, String enumName) {
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> RangerServiceDefValidator.isValidEnumElements(%s, %s)", enumElementsDefs, failures));
		}
		
		boolean valid = true;
		if (CollectionUtils.isEmpty(enumElementsDefs)) {
			LOG.debug("Enum elements list passed in was null/empty!");
		} else {
			// enum element names should be valid and distinct
			Set<String> elementNames = new HashSet<String>();
			Set<Long> ids = new HashSet<Long>();
			for (RangerEnumElementDef elementDef : enumElementsDefs) {
				if (elementDef == null) {
					ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_ENUM_DEF_NULL_ENUM_ELEMENT;
					failures.add(new ValidationFailureDetailsBuilder()
						.field("enum element")
						.subField(enumName)
						.isMissing()
						.errorCode(error.getErrorCode())
						.becauseOf(error.getMessage(enumName))
						.build());
					valid = false;
				} else {
					valid = isUnique(elementDef.getName(), enumName, elementNames, "enum element name", "enum elements", failures) && valid;
					valid = isUnique(elementDef.getItemId(), enumName, ids, "enum element itemId", "enum elements", failures) && valid;
				}
			}
		}
		
		if(LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== RangerServiceDefValidator.isValidEnumElements(%s, %s): %s", enumElementsDefs, failures, valid));
		}
		return valid;
	}
}
