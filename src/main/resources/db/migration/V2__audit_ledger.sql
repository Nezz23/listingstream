-- Tamper-evident audit ledger. Every snapshot decision becomes one block: its entries (the decision itself plus
-- every listing whose Local Law 18 status changed) are hashed into a Merkle root, the block header links to the
-- previous block's hash, and the header hash is signed with the service's ECDSA key. Changing any stored entry,
-- reordering blocks or forging a block without the private key is detected by GET /api/ledger/verify.
create table audit_blocks (
    height        integer     primary key check (height >= 0),
    snapshot_date date        not null unique,
    prev_hash     char(64)    not null,
    merkle_root   char(64)    not null,
    entry_count   integer     not null check (entry_count > 0),
    created_ms    bigint      not null,
    block_hash    char(64)    not null unique,
    signature     text        not null
);

create table audit_entries (
    block_height  integer     not null references audit_blocks (height),
    position      integer     not null,
    kind          text        not null check (kind in ('snapshot', 'status_change')),
    listing_id    bigint,
    old_status    text,
    new_status    text,
    detail        text,
    entry_hash    char(64)    not null,
    primary key (block_height, position)
);

create index audit_entries_listing on audit_entries (listing_id) where listing_id is not null;
