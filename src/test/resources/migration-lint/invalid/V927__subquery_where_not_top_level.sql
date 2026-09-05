-- Fixture (skillars-deferred-92 code review): a subquery's own WHERE used to satisfy the outer
-- UPDATE's bounding-clause check. This UPDATE has no bounding predicate on main.widget at all.
SET lock_timeout = '5s';
UPDATE main.widget SET label = (SELECT v FROM main.other_widget_labels WHERE main.other_widget_labels.id = 1);
