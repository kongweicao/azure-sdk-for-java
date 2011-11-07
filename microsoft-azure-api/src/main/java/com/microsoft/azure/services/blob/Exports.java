package com.microsoft.azure.services.blob;

import com.microsoft.azure.configuration.builder.Builder;

public class Exports implements Builder.Exports {
    public void register(Builder.Registry registry) {
        registry.add(BlobService.class, BlobServiceImpl.class);
        registry.add(BlobServiceForJersey.class);
        registry.add(BlobSharedKeyLiteFilter.class);
    }
}
