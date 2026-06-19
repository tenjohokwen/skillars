package com.softropic.skillars.platform.development.contract;

import java.util.List;

public record RadarDisplayResponse(
    List<SkillRadarEntry> skills   // one entry per skill_definition (all 15, nulls where no data)
) {}
