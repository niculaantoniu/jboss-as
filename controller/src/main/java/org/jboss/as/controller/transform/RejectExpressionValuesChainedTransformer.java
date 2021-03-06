/*
* JBoss, Home of Professional Open Source.
* Copyright 2012, Red Hat Middleware LLC, and individual contributors
* as indicated by the @author tags. See the copyright.txt file in the
* distribution for a full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.jboss.as.controller.transform;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ControllerLogger;
import org.jboss.as.controller.ControllerMessages;
import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.transform.chained.ChainedResourceTransformationContext;
import org.jboss.as.controller.transform.chained.ChainedResourceTransformer;
import org.jboss.as.controller.transform.chained.ChainedResourceTransformerEntry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;
/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @deprecated Experimental, may changed
 * @see ChainedResourceTransformer
 */
@Deprecated
@SuppressWarnings("deprecation")
public class RejectExpressionValuesChainedTransformer implements ChainedResourceTransformerEntry, OperationTransformer {

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile(".*\\$\\{.*\\}.*");

    private final Set<String> attributeNames;
    private final OperationTransformer writeAttributeTransformer = new WriteAttributeTransformer();


    public RejectExpressionValuesChainedTransformer(AttributeDefinition... attributes) {
        final Set<String> names = new HashSet<String>();
        for(final AttributeDefinition def : attributes) {
            names.add(def.getName());
        }
        this.attributeNames = names;
    }

    public RejectExpressionValuesChainedTransformer(Set<String> attributeNames) {
        this.attributeNames = attributeNames;
    }

    public RejectExpressionValuesChainedTransformer(String... attributeNames) {
        this.attributeNames = new HashSet<String>();
        this.attributeNames.addAll(Arrays.asList(attributeNames));
    }

    /**
     * Get a "write-attribute" operation transformer.
     *
     * @return a write attribute operation transformer
     */
    public OperationTransformer getWriteAttributeTransformer() {
        return writeAttributeTransformer;
    }

    @Override
    public TransformedOperation transformOperation(final TransformationContext context, final PathAddress address, final ModelNode operation) throws OperationFailedException {
        // Check the model
        final Set<String> attributes = checkModel(operation);
        final boolean reject = attributes.size() > 0;
        final OperationRejectionPolicy rejectPolicy;
        if(reject) {
            rejectPolicy = new OperationRejectionPolicy() {
                @Override
                public boolean rejectOperation(ModelNode preparedResult) {
                    // Reject successful operations
                    return true;
                }

                @Override
                public String getFailureDescription() {
                    // TODO OFE.getMessage
                    try {
                        return logError(context, address, attributes, operation);
                    } catch (OperationFailedException e) {
                        //This will not happen
                        return null;
                    }
                }
            };
        } else {
            rejectPolicy = OperationTransformer.DEFAULT_REJECTION_POLICY;
        }
        // Return untransformed
        return new TransformedOperation(operation, rejectPolicy, OperationResultTransformer.ORIGINAL_RESULT);
    }

    @Override
    public void transformResource(final ChainedResourceTransformationContext context, final PathAddress address,
                                  final Resource resource) throws OperationFailedException {
        // Check the model
        final ModelNode model = resource.getModel();
        final Set<String> attributes = checkModel(model);
        if (attributes.size() > 0) {
            logError(context, address, attributes, null);
        }
    }

    /**
     * Check the model for expression values.
     *
     * @param model the model
     * @return the attribute containing an expression
     */
    protected Set<String> checkModel(final ModelNode model) throws OperationFailedException {
        final Set<String> attributes = new HashSet<String>();
        for(final String attribute : attributeNames) {
            if(model.hasDefined(attribute)) {
                if(checkForExpression(model.get(attribute))) {
                    attributes.add(attribute);
                }
            }
        }
        return attributes;
    }

    /**
     * Check an attribute for expressions.
     *
     * @param node the attribute value
     * @return whether an expression was found or not
     */
    protected boolean checkForExpression(final ModelNode node) {
        if (!node.isDefined()) {
            return false;
        }

        final ModelNode resolved = node.clone();
        if (node.getType() == ModelType.EXPRESSION || node.getType() == ModelType.STRING) {
            return checkForExpression(resolved.asString());
        } else if (node.getType() == ModelType.OBJECT) {
            for (Property prop : resolved.asPropertyList()) {
                if(checkForExpression(prop.getValue())) {
                    return true;
                }
            }
        } else if (node.getType() == ModelType.LIST) {
            for (ModelNode current : resolved.asList()) {
                if(checkForExpression(current)) {
                    return true;
                }
            }
        } else if (node.getType() == ModelType.PROPERTY) {
            return checkForExpression(resolved.asProperty().getValue());
        }
        return false;
    }

    class WriteAttributeTransformer implements OperationTransformer {

        @Override
        public TransformedOperation transformOperation(final TransformationContext context, final PathAddress address, final ModelNode operation) throws OperationFailedException {
            final String attribute = operation.require(ModelDescriptionConstants.NAME).asString();
            boolean containsExpression = false;
            if(attributeNames.contains(attribute)) {
                if(operation.hasDefined(ModelDescriptionConstants.VALUE)) {
                    containsExpression = checkForExpression(operation.get(ModelDescriptionConstants.VALUE));
                }
            }
            final boolean rejectResult = containsExpression;
            if(rejectResult) {
                // Create the rejection policy
                final OperationRejectionPolicy rejectPolicy = new OperationRejectionPolicy() {
                    @Override
                    public boolean rejectOperation(ModelNode preparedResult) {
                        // Reject successful operations
                        return true;
                    }

                    @Override
                    public String getFailureDescription() {
                        try {
                            return logError(context, address, Collections.singleton(attribute), operation);
                        } catch (OperationFailedException e) {
                            //This will not happen
                            return null;
                        }
                    }
                };
                return new TransformedOperation(operation, rejectPolicy, OperationResultTransformer.ORIGINAL_RESULT);
            }
            // In case it's not an expressions just forward unmodified
            return new TransformedOperation(operation, OperationResultTransformer.ORIGINAL_RESULT);
        }
    }

    protected boolean checkForExpression(final String value) {
        return EXPRESSION_PATTERN.matcher(value).matches();
    }

    private String logError(TransformationContext context, PathAddress pathAddress, Set<String> attributes, ModelNode op) throws OperationFailedException {

        //TODO the determining of whether the version is 1.4.0, i.e. knows about ignored resources or not could be moved to a utility method

        final TransformationTarget tgt = context.getTarget();
        final String hostName = tgt.getHostName();
        final ModelVersion coreVersion = tgt.getVersion();
        final String subsystemName = findSubsystemVersion(pathAddress);
        final ModelVersion usedVersion = subsystemName == null ? coreVersion : tgt.getSubsystemVersion(subsystemName);

        //For 7.1.x, we have no idea if the slave has ignored the resource or not. On 7.2.x the slave registers the ignored resources as
        //part of the registration process so we have a better idea and can throw errors if the slave was ignored
        if (op == null) {
            if (coreVersion.getMajor() >= 1 && coreVersion.getMinor() >= 4) {
                //We are 7.2.x so we should throw an error
                if (subsystemName != null) {
                    throw ControllerMessages.MESSAGES.rejectExpressionSubsystemModelResourceTransformerFoundExpressions(pathAddress, hostName, subsystemName, usedVersion, attributes);
                }
                throw ControllerMessages.MESSAGES.rejectExpressionCoreModelResourceTransformerFoundExpressions(pathAddress, hostName, usedVersion, attributes);
            }
        }

        if (op == null) {
            if (subsystemName != null) {
                ControllerLogger.TRANSFORMER_LOGGER.rejectExpressionSubsystemModelResourceTransformerFoundExpressions(pathAddress, hostName, subsystemName, usedVersion, attributes);
            } else {
                ControllerLogger.TRANSFORMER_LOGGER.rejectExpressionCoreModelResourceTransformerFoundExpressions(pathAddress, hostName, usedVersion, attributes);
            }
            return null;
        } else {
            if (subsystemName != null) {
                return ControllerMessages.MESSAGES.rejectExpressionSubsystemModelOperationTransformerFoundExpressions(op, pathAddress, hostName, subsystemName, usedVersion, attributes).getMessage();
            } else {
                return ControllerMessages.MESSAGES.rejectExpressionCoreModelOperationTransformerFoundExpressions(op, pathAddress, hostName, usedVersion, attributes).getMessage();
            }
        }
    }


    private String findSubsystemVersion(PathAddress pathAddress) {
        for (PathElement element : pathAddress) {
            if (element.getKey().equals(SUBSYSTEM)) {
                return element.getValue();
            }
        }
        return null;
    }
}
