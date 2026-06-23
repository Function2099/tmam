package com.tmam.model;

public record PortConflict(int port, String type, String projectA, String projectB) {
}
