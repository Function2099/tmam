package com.tmam.dto;

import com.tmam.model.InstanceStatus;

public record TomcatStatusView(InstanceStatus status, boolean externallyManaged) {
}
