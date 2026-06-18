package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.SessionDnaScore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SessionDnaCalculator {

    public SessionDnaScore calculate(List<DrillMetadata> drills) {
        if (drills == null || drills.isEmpty()) {
            return new SessionDnaScore(0, 0, 0, 0, 0);
        }
        int count = drills.size();
        double sumTechnical = 0, sumPhysical = 0, sumCognitive = 0, sumMatchRealism = 0;
        int weakFootCount = 0;
        for (DrillMetadata m : drills) {
            sumTechnical += (m.intensity() + m.pressureLevel()) / 2.0;
            sumPhysical += m.intensity();
            sumCognitive += m.cognitiveLoad();
            sumMatchRealism += m.matchRealism();
            if (m.weakFootBias()) weakFootCount++;
        }
        return new SessionDnaScore(
            mapToScore(sumTechnical / count),
            mapToScore(sumPhysical / count),
            mapToScore(sumCognitive / count),
            mapToScore(sumMatchRealism / count),
            Math.round((float) weakFootCount / count * 100)
        );
    }

    private int mapToScore(double avg) {
        return Math.max(0, Math.min(100, (int) Math.round((avg - 1.0) * 25.0)));
    }
}
