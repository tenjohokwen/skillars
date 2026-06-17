INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
  (63, 'feature.drillVideoUpload.enabled.SCOUT',      'false',     'STRING', 'Drill video upload gate: Scout tier'),
  (64, 'feature.drillVideoUpload.enabled.INSTRUCTOR', 'true',      'STRING', 'Drill video upload gate: Instructor tier'),
  (65, 'feature.drillVideoUpload.enabled.ACADEMY',    'true',      'STRING', 'Drill video upload gate: Academy tier'),
  (66, 'video.drillDemo.maxDurationSeconds',          '120',       'STRING', 'Drill demo max duration (seconds)'),
  (67, 'video.drillDemo.maxSizeBytes',                '524288000', 'STRING', 'Drill demo max size (500 MB in bytes)');
