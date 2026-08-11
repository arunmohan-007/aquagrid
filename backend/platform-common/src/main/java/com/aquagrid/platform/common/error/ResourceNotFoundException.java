package com.aquagrid.platform.common.error;

import java.util.Map;

/** Raised when an addressed resource does not exist, or is not visible to the current tenant. */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceType, Object identifier) {
        super(ErrorCode.RESOURCE_NOT_FOUND,
                "%s '%s' was not found".formatted(resourceType, identifier),
                Map.of("resourceType", resourceType));
    }

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
