package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.NodeTypeRegistry;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NoSuchNodeTypeException;

/**
 * A property the module writes, as configured: its name, and the mixin that declares it when the node must
 * get that mixin first. A {@code null} mixin means the node's own types already declare the property.
 *
 * @param property the property name, such as {@code jahia_alt}
 * @param mixin    the mixin to add before writing, such as {@code jmix:jahiaAlt}, or {@code null}
 */
record TargetProperty(String property, String mixin) {

    /**
     * @return the definition that would hold the property on {@code node}, or {@code null} when none is
     *         declared under that exact name; a residual {@code *} definition never counts, so a misspelled
     *         property is refused instead of being written as a stray one
     */
    ExtendedPropertyDefinition definitionOn(JCRNodeWrapper node) throws RepositoryException {
        ExtendedPropertyDefinition definition;
        if (mixin != null && !node.isNodeType(mixin)) {
            try {
                definition = NodeTypeRegistry.getInstance().getNodeType(mixin).getPropertyDefinitionsAsMap()
                        .get(property);
            } catch (NoSuchNodeTypeException e) {
                return null;
            }
        } else {
            definition = node.getApplicablePropertyDefinition(property);
        }
        return definition != null && property.equals(definition.getName()) ? definition : null;
    }

    /** @return whether the definition holds text: a string, or an untyped property */
    static boolean holdsText(ExtendedPropertyDefinition definition) {
        int type = definition.getRequiredType();
        return type == PropertyType.STRING || type == PropertyType.UNDEFINED;
    }

    @Override
    public String toString() {
        return mixin == null ? property : property + " (mixin " + mixin + ")";
    }
}
