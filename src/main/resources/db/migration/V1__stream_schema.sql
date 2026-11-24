-- Every valid event lands here first. The primary key makes ingestion idempotent: a redelivered or replayed
-- event is a no-op, so at-least-once delivery from Kafka gives exactly-once results in the tables.
create table staged_listings (
    snapshot_date    date        not null,
    listing_id       bigint      not null,
    name             text,
    host_id          bigint,
    neighbourhood    text,
    borough          text,
    property_type    text,
    room_type        text,
    accommodates     integer,
    minimum_nights   integer,
    maximum_nights   integer,
    instant_bookable boolean,
    license          text,
    ll18_status      text        not null,
    price_usd        numeric(12, 2),
    latitude         double precision,
    longitude        double precision,
    received_at      timestamptz not null default now(),
    primary key (snapshot_date, listing_id)
);

-- One row per scrape: how many events the producer sent, and what happened to the snapshot.
create table snapshots (
    snapshot_date   date        primary key,
    expected_events bigint      not null,
    status          text        not null default 'pending'
                    check (status in ('pending', 'applied', 'quarantined')),
    reason          text,
    staged_rows     bigint,
    versions_opened integer,
    versions_closed integer,
    merge_millis    bigint,
    marker_at       timestamptz not null default now(),
    decided_at      timestamptz
);

-- Type 2 slowly changing history: a new version whenever a tracked attribute changes, closed when the listing
-- changes again or disappears from a scrape.
create table listing_versions (
    version_id       bigserial   primary key,
    listing_id       bigint      not null,
    name             text,
    host_id          bigint,
    neighbourhood    text,
    borough          text,
    property_type    text,
    room_type        text,
    accommodates     integer,
    minimum_nights   integer,
    maximum_nights   integer,
    instant_bookable boolean,
    license          text,
    ll18_status      text        not null,
    valid_from       date        not null,
    valid_to         date,
    is_current       boolean     not null default true,
    check (valid_to is null or valid_to > valid_from)
);

create unique index listing_versions_one_current on listing_versions (listing_id) where is_current;
create index listing_versions_listing on listing_versions (listing_id, valid_from);
create index listing_versions_current_filter on listing_versions (borough, ll18_status) where is_current;

-- Events the contract refused. The same payload also goes to the Kafka dead-letter topic.
create table dead_letters (
    id             bigserial   primary key,
    snapshot_date  date,
    source_topic   text        not null,
    source_partition integer   not null,
    source_offset  bigint      not null,
    event_key      text,
    payload        text,
    error          text        not null,
    received_at    timestamptz not null default now(),
    unique (source_topic, source_partition, source_offset)
);
