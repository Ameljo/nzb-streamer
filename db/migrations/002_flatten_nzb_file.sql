-- Flattens NzbFile's segments and groups.
--
-- Before: NzbFile -> Segments (1:1) -> Segment (1:many, own table, own rows)
--         NzbFile -> Groups (1:1) -> group_name (element collection, own table)
-- After:  NzbFile.segments  jsonb column, one row per post instead of one per segment
--         NzbFile.groups    element collection directly on nzb_file (nzb_file_group)
--
-- The jsonb shape matches what SegmentPrototype/Jackson already produce and consume
-- (validated end-to-end via StreamingCorrectnessCheckPrototype): a JSON array of
-- {"value", "bytes", "number", "size", "startPosition"} objects, ordered by number --
-- the same order @OrderBy("number") used to guarantee on the old Segment collection.
--
-- A fresh database gets the new column/table from spring.jpa.hibernate.ddl-auto=update
-- automatically; this script is for migrating an existing database's data before the
-- old tables are dropped, since ddl-auto=update never migrates data or drops columns.
--
-- Run once: psql -f 002_flatten_nzb_file.sql, or paste into psql.

BEGIN;

ALTER TABLE nzb_file ADD COLUMN IF NOT EXISTS segments jsonb;

CREATE TABLE IF NOT EXISTS nzb_file_group (
    nzb_file_id uuid NOT NULL REFERENCES nzb_file(id),
    group_name  varchar(255)
);

-- Backfill segments, aggregated per post and ordered the way @OrderBy("number") was.
UPDATE nzb_file nf
SET segments = sub.segments
FROM (
    SELECT s.segments_id AS segments_id,
           jsonb_agg(
               jsonb_build_object(
                   'value', s.value,
                   'bytes', s.bytes,
                   'number', s.number,
                   'size', s.size,
                   'startPosition', s.start_position
               ) ORDER BY s.number
           ) AS segments
    FROM segment s
    GROUP BY s.segments_id
) sub
WHERE nf.segments_id = sub.segments_id;

-- Backfill groups.
INSERT INTO nzb_file_group (nzb_file_id, group_name)
SELECT nf.id, gg.group_name
FROM nzb_file nf
JOIN groups_group gg ON gg.groups_id = nf.groups_id;

-- Drop the old columns and tables now that everything is migrated.
ALTER TABLE nzb_file DROP COLUMN groups_id;
ALTER TABLE nzb_file DROP COLUMN segments_id;

DROP TABLE groups_group;
DROP TABLE groups;
DROP TABLE segment;
DROP TABLE segments;

COMMIT;
