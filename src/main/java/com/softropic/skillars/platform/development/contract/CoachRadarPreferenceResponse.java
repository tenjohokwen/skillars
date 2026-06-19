package com.softropic.skillars.platform.development.contract;

import java.util.List;

public record CoachRadarPreferenceResponse(
    List<String> selectedSkillCodes  // empty list = no stored preference (frontend uses all 15)
) {}
