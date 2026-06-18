package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.platform.session.contract.DrillMetadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class EquipmentListService {

    public List<String> generate(List<DrillMetadata> drills) {
        if (drills == null || drills.isEmpty()) return List.of();
        return drills.stream()
            .flatMap(m -> m.equipmentRequired() != null
                ? m.equipmentRequired().stream() : Stream.empty())
            .map(String::toLowerCase)
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }
}
