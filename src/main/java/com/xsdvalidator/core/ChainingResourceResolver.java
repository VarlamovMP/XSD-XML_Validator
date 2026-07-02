package com.xsdvalidator.core;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

public class ChainingResourceResolver implements LSResourceResolver {

    private final LSResourceResolver[] resolvers;

    public ChainingResourceResolver(LSResourceResolver... resolvers) {
        this.resolvers = resolvers;
    }

    @Override
    public LSInput resolveResource(
            String type,
            String namespaceURI,
            String publicId,
            String systemId,
            String baseURI
    ) {
        for (LSResourceResolver resolver : resolvers) {
            LSInput input = resolver.resolveResource(type, namespaceURI, publicId, systemId, baseURI);
            if (input != null) {
                return input;
            }
        }
        return null;
    }
}
