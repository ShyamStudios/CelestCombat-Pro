package com.shyamstudio.celestCombatPro.updates;

import org.jetbrains.annotations.NotNull;

public class Version implements Comparable<Version> {
    private final int[] parts;
    private static final int MAX_PARTS = 4;

    public Version(String version) {
        version = version.replaceAll("[^0-9.].*$", "")
                .replaceAll("^[^0-9]*", "");

        String[] split = version.split("\\.");
        parts = new int[MAX_PARTS];

        for (int i = 0; i < MAX_PARTS; i++) {
            if (i < split.length) {
                try {
                    parts[i] = Integer.parseInt(split[i]);
                } catch (NumberFormatException e) {
                    parts[i] = 0;
                }
            } else {
                parts[i] = 0;
            }
        }
    }


    @Override
    public int compareTo(@NotNull Version other) {
        for (int i = 0; i < MAX_PARTS; i++) {
            if (parts[i] != other.parts[i]) {
                return parts[i] - other.parts[i];
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }

        if (parts[3] > 0) {
            sb.append('.').append(parts[3]);
        }

        return sb.toString();
    }
}