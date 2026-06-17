-- Story 4.1: Foundation 20 Platform Library drills (4 packs × 5 drills)
-- No drill_video_refs rows — video association is Story 4.3
-- trans_key references frontend i18n keys under the sessDrill namespace

-- ============================================================
-- Pack 1: Master Touch (ball mastery / control)
-- ============================================================
INSERT INTO session.drills (id, name, description, library_type, owner_coach_id, status, metadata, trans_key) VALUES
(gen_random_uuid(), 'Toe Taps', 'Rapid alternating toe taps on top of the ball to develop quick feet and ball feel.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["ball_mastery"],"secondarySkills":["coordination","balance"],"skillWeighting":{"ball_mastery":70,"coordination":20,"balance":10},"repDensity":40,"intensity":2,"pressureLevel":1,"cognitiveLoad":1,"matchRealism":1,"weakFootBias":true,"difficultyTier":"U8","equipmentRequired":["ball"],"recommendedGroupSize":"1–4","coachingPoints":["Keep the ball close under your feet","Use the front pad of each toe","Increase speed gradually","Stay light on your feet"]}',
 'sessDrill.toeTaps'),

(gen_random_uuid(), 'Inside-Outside Roll', 'Roll the ball across the body using inside-outside foot alternations to build touch and control.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["ball_mastery","close_control"],"secondarySkills":["footwork"],"skillWeighting":{"ball_mastery":60,"close_control":30,"footwork":10},"repDensity":20,"intensity":2,"pressureLevel":1,"cognitiveLoad":2,"matchRealism":2,"weakFootBias":true,"difficultyTier":"U10","equipmentRequired":["ball"],"recommendedGroupSize":"1–6","coachingPoints":["Soft touch on every contact","Eyes up between touches","Stay balanced over the ball","Progress to both feet equally"]}',
 'sessDrill.insideOutsideRoll'),

(gen_random_uuid(), 'L-Shape Mastery', 'Player uses sole to pull back, then inside foot to drag the ball in an L-pattern.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["ball_mastery","close_control"],"secondarySkills":["change_of_direction"],"skillWeighting":{"ball_mastery":55,"close_control":30,"change_of_direction":15},"repDensity":16,"intensity":2,"pressureLevel":1,"cognitiveLoad":2,"matchRealism":2,"weakFootBias":false,"difficultyTier":"U10","equipmentRequired":["ball","cones"],"recommendedGroupSize":"1–4","coachingPoints":["Plant foot points in direction of travel","Sole pull stays behind the body line","Drive away with purpose after the shape","Build speed only after technique is clean"]}',
 'sessDrill.lShapeMastery'),

(gen_random_uuid(), 'Foundation Juggling Sequence', 'Structured juggling progression: thigh-to-thigh, foot-to-thigh, continuous foot juggle.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["ball_mastery","first_touch"],"secondarySkills":["coordination","aerial_control"],"skillWeighting":{"ball_mastery":50,"first_touch":35,"coordination":15},"repDensity":30,"intensity":1,"pressureLevel":1,"cognitiveLoad":2,"matchRealism":1,"weakFootBias":true,"difficultyTier":"U12","equipmentRequired":["ball"],"recommendedGroupSize":"1–8","coachingPoints":["Cushion the ball on contact — don''t stab","Minimal knee bend to control height","Use laces not toes for clean strikes","Track the ball all the way to contact"]}',
 'sessDrill.foundationJugglingSequence'),

(gen_random_uuid(), 'Cone Slalom Ball Mastery', 'Player dribbles a cone slalom using only inside and outside touches.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["close_control","dribbling"],"secondarySkills":["ball_mastery","agility"],"skillWeighting":{"close_control":50,"dribbling":35,"ball_mastery":15},"repDensity":10,"intensity":3,"pressureLevel":2,"cognitiveLoad":2,"matchRealism":3,"weakFootBias":false,"difficultyTier":"U12","equipmentRequired":["ball","cones"],"recommendedGroupSize":"2–8","coachingPoints":["Keep the ball within 1 foot of your body","Lean into each gate","Use alternate feet at each cone","Accelerate between gates"]}',
 'sessDrill.coneSlalomBallMastery');

-- ============================================================
-- Pack 2: Sniper (finishing / shooting)
-- ============================================================
INSERT INTO session.drills (id, name, description, library_type, owner_coach_id, status, metadata, trans_key) VALUES
(gen_random_uuid(), 'Static Finish — Inside Foot', 'Stationary shooting practice using the inside foot for placement and accuracy.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["finishing","shooting_technique"],"secondarySkills":["accuracy"],"skillWeighting":{"finishing":60,"shooting_technique":30,"accuracy":10},"repDensity":20,"intensity":2,"pressureLevel":1,"cognitiveLoad":1,"matchRealism":2,"weakFootBias":true,"difficultyTier":"U8","equipmentRequired":["ball","goal"],"recommendedGroupSize":"2–6","coachingPoints":["Lock the ankle on contact","Strike through the middle of the ball","Non-kicking foot beside the ball","Follow through toward target"]}',
 'sessDrill.staticFinishInsideFoot'),

(gen_random_uuid(), 'Turn and Shoot', 'Receive a pass with back to goal, turn, and shoot first time.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["finishing","first_touch"],"secondarySkills":["turning","decision_making"],"skillWeighting":{"finishing":45,"first_touch":35,"turning":20},"repDensity":12,"intensity":3,"pressureLevel":2,"cognitiveLoad":3,"matchRealism":4,"weakFootBias":false,"difficultyTier":"U14","equipmentRequired":["ball","goal","cones"],"recommendedGroupSize":"3–8","coachingPoints":["Check shoulder before the ball arrives","Decide turn direction before receiving","Positive first touch away from goal","Strike early — don''t over-touch"]}',
 'sessDrill.turnAndShoot'),

(gen_random_uuid(), 'Shooting on the Move', 'Player receives a lateral pass while moving and finishes on goal without breaking stride.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["shooting_technique","finishing"],"secondarySkills":["movement","coordination"],"skillWeighting":{"shooting_technique":50,"finishing":35,"movement":15},"repDensity":14,"intensity":3,"pressureLevel":2,"cognitiveLoad":2,"matchRealism":4,"weakFootBias":true,"difficultyTier":"U14","equipmentRequired":["ball","goal","cones"],"recommendedGroupSize":"2–6","coachingPoints":["Keep head steady through contact","Plant foot pointed at the target","Strike before pace is lost","Aim for corners — power without control is wasted"]}',
 'sessDrill.shootingOnTheMove'),

(gen_random_uuid(), '1v1 Finishing Under Pressure', 'Attacker receives, beats a passive defender, and shoots within a 5-second window.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["finishing","dribbling_under_pressure"],"secondarySkills":["decision_making","composure"],"skillWeighting":{"finishing":40,"dribbling_under_pressure":35,"decision_making":25},"repDensity":10,"intensity":4,"pressureLevel":4,"cognitiveLoad":4,"matchRealism":5,"weakFootBias":false,"difficultyTier":"U16","equipmentRequired":["ball","goal","cones"],"recommendedGroupSize":"4–10","coachingPoints":["Commit the defender before the move","Create the angle — don''t shoot from straight on","Stay calm — your foot technique does not change under pressure","Score with purpose not panic"]}',
 'sessDrill.oneVOneFinishingUnderPressure'),

(gen_random_uuid(), 'Volley Finishing', 'Players practice volleys from crossed balls or throws, focusing on technique and timing.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["shooting_technique","aerial_control"],"secondarySkills":["finishing","timing"],"skillWeighting":{"shooting_technique":50,"aerial_control":30,"timing":20},"repDensity":10,"intensity":3,"pressureLevel":2,"cognitiveLoad":3,"matchRealism":4,"weakFootBias":true,"difficultyTier":"U16","equipmentRequired":["ball","goal"],"recommendedGroupSize":"3–8","coachingPoints":["Watch the ball all the way to contact","Get side-on — do not face the ball square","Keep toes down and lock ankle","Aim across goal for placement"]}',
 'sessDrill.volleyFinishing');

-- ============================================================
-- Pack 3: Escape Artist (dribbling / turns under pressure)
-- ============================================================
INSERT INTO session.drills (id, name, description, library_type, owner_coach_id, status, metadata, trans_key) VALUES
(gen_random_uuid(), 'Cruyff Turn', 'Classic feint: fake to pass or cross, pull ball behind standing leg with inside foot.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["dribbling","turning"],"secondarySkills":["deception","close_control"],"skillWeighting":{"dribbling":50,"turning":35,"deception":15},"repDensity":14,"intensity":2,"pressureLevel":2,"cognitiveLoad":2,"matchRealism":3,"weakFootBias":false,"difficultyTier":"U12","equipmentRequired":["ball","cones"],"recommendedGroupSize":"2–8","coachingPoints":["Sell the fake with your body lean","Drag behind the standing leg — not across","Explode out in new direction immediately","Head up after turn to make next decision"]}',
 'sessDrill.cruyffTurn'),

(gen_random_uuid(), 'Step-Over to Accelerate', 'Use step-over(s) to unbalance a defender, then burst into space with outside foot.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["dribbling","skill_moves"],"secondarySkills":["agility","acceleration"],"skillWeighting":{"dribbling":55,"skill_moves":30,"acceleration":15},"repDensity":16,"intensity":3,"pressureLevel":2,"cognitiveLoad":2,"matchRealism":4,"weakFootBias":true,"difficultyTier":"U14","equipmentRequired":["ball","cones"],"recommendedGroupSize":"2–8","coachingPoints":["Circle the ball — don''t kick it","Plant foot stays behind ball line","Drive outside foot away immediately","Lean opposite direction to the step-over"]}',
 'sessDrill.stepOverToAccelerate'),

(gen_random_uuid(), 'Elastico / Flip-Flap', 'Touch ball outside then whip inside with same foot in one fast motion to beat the defender.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["skill_moves","close_control"],"secondarySkills":["dribbling","deception"],"skillWeighting":{"skill_moves":60,"close_control":25,"deception":15},"repDensity":12,"intensity":3,"pressureLevel":2,"cognitiveLoad":3,"matchRealism":3,"weakFootBias":false,"difficultyTier":"U16","equipmentRequired":["ball","cones"],"recommendedGroupSize":"1–6","coachingPoints":["The outside touch is the fake","Whip inside must be sharp and low","Keep ball very close — inches not feet","Practise slowly first before adding speed"]}',
 'sessDrill.elasticoFlipFlap'),

(gen_random_uuid(), 'Pressure Escape Rondo (3v1)', 'Three passers keep the ball in a small square from one defender; after 5 passes, player must dribble out.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["dribbling_under_pressure","close_control"],"secondarySkills":["decision_making","awareness","passing"],"skillWeighting":{"dribbling_under_pressure":40,"close_control":30,"decision_making":30},"repDensity":8,"intensity":4,"pressureLevel":5,"cognitiveLoad":5,"matchRealism":5,"weakFootBias":false,"difficultyTier":"U16","equipmentRequired":["ball","cones"],"recommendedGroupSize":"4","coachingPoints":["Protect the ball with your body","Take the ball away from pressure — not into it","Head up: see all three passers","Disguise your exit before you take it"]}',
 'sessDrill.pressureEscapeRondo3v1'),

(gen_random_uuid(), 'Body Feint and Drive', 'Drop shoulder to fake one direction, drive past passive defender with outside foot on opposite side.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["dribbling","deception"],"secondarySkills":["close_control","agility"],"skillWeighting":{"dribbling":55,"deception":35,"close_control":10},"repDensity":14,"intensity":3,"pressureLevel":3,"cognitiveLoad":3,"matchRealism":4,"weakFootBias":true,"difficultyTier":"U14","equipmentRequired":["ball","cones"],"recommendedGroupSize":"2–8","coachingPoints":["Drop shoulder convincingly — commit to the fake","Drive must be explosive — not cautious","Keep ball close to the outside foot","Look up for the next action immediately"]}',
 'sessDrill.bodyFeintAndDrive');

-- ============================================================
-- Pack 4: Wall (passing / receiving)
-- ============================================================
INSERT INTO session.drills (id, name, description, library_type, owner_coach_id, status, metadata, trans_key) VALUES
(gen_random_uuid(), 'Pass and Move (2-Touch)', 'Simple pass and move in pairs — receive, control, pass, move to new position.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["passing","first_touch"],"secondarySkills":["movement","positioning"],"skillWeighting":{"passing":50,"first_touch":35,"movement":15},"repDensity":30,"intensity":2,"pressureLevel":1,"cognitiveLoad":2,"matchRealism":3,"weakFootBias":true,"difficultyTier":"U8","equipmentRequired":["ball","cones"],"recommendedGroupSize":"4–12","coachingPoints":["Open body before the ball arrives","Pass into the path of your partner''s run","Move immediately after the pass","Weight of pass must match distance"]}',
 'sessDrill.passAndMove2Touch'),

(gen_random_uuid(), 'Wall Pass (1-2 Combination)', 'Player plays pass to wall player, receives return ball in stride, and continues forward.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["passing","combination_play"],"secondarySkills":["movement","timing"],"skillWeighting":{"passing":45,"combination_play":40,"movement":15},"repDensity":20,"intensity":3,"pressureLevel":2,"cognitiveLoad":3,"matchRealism":5,"weakFootBias":false,"difficultyTier":"U12","equipmentRequired":["ball","cones"],"recommendedGroupSize":"3–8","coachingPoints":["Angle body before playing the wall pass","Drive into space immediately after your pass","Wall player returns with one touch if possible","Receiver controls ball into stride — not dead"]}',
 'sessDrill.wallPassOneTwoCombination'),

(gen_random_uuid(), 'Switch of Play from Central', 'Central player receives, spins, and switches play across to opposite wide player under time pressure.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["passing","switching_play"],"secondarySkills":["first_touch","awareness","decision_making"],"skillWeighting":{"passing":50,"switching_play":30,"awareness":20},"repDensity":12,"intensity":3,"pressureLevel":3,"cognitiveLoad":4,"matchRealism":5,"weakFootBias":false,"difficultyTier":"U14","equipmentRequired":["ball","cones","bibs"],"recommendedGroupSize":"5–10","coachingPoints":["Scan before receiving","First touch orients toward target","Long switch must be weighted correctly","Receiver checks run to meet the ball"]}',
 'sessDrill.switchOfPlayFromCentral'),

(gen_random_uuid(), 'Third-Man Run Combination', 'Two players combine to play in a third runner arriving late into the final third.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["combination_play","passing"],"secondarySkills":["movement","timing","awareness"],"skillWeighting":{"combination_play":50,"passing":30,"movement":20},"repDensity":8,"intensity":4,"pressureLevel":3,"cognitiveLoad":5,"matchRealism":5,"weakFootBias":false,"difficultyTier":"U16","equipmentRequired":["ball","cones","bibs","goal"],"recommendedGroupSize":"6–12","coachingPoints":["Third runner must time the run — not sprint early","Hold ball long enough to draw the press","Pass must go in behind the line","Third man should be onside at the moment of the pass"]}',
 'sessDrill.thirdManRunCombination'),

(gen_random_uuid(), 'Receiving in the Half-Turn', 'Player checks to the ball, receives between the lines in a half-turned body position to keep play moving forward.', 'PLATFORM', NULL, 'ACTIVE',
 '{"primarySkills":["first_touch","receiving_under_pressure"],"secondarySkills":["awareness","positioning","passing"],"skillWeighting":{"first_touch":45,"receiving_under_pressure":35,"awareness":20},"repDensity":16,"intensity":3,"pressureLevel":4,"cognitiveLoad":4,"matchRealism":5,"weakFootBias":true,"difficultyTier":"U16","equipmentRequired":["ball","cones","bibs"],"recommendedGroupSize":"4–8","coachingPoints":["Open body sideways before ball arrives","Check shoulder for pressure before receiving","First touch goes forward or away from pressure","Head up instantly to play the next pass"]}',
 'sessDrill.receivingInTheHalfTurn');
