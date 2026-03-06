package com.intellibase.server.domain.dto;

import lombok.Data;

@Data
public class UpdateKbRequest {

    private String name;

    private String description;

    private String status;

}
