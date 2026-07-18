-- Reading plans were removed from the product. Drop dependent items first so
-- existing installations can upgrade without violating foreign keys.
DROP TABLE IF EXISTS reading_plan_item;
DROP TABLE IF EXISTS reading_plan;
