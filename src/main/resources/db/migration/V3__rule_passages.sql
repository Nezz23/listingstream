-- Plain-language rule passages the assistant retrieves and cites as [R1], [R2], ... Written for this project as
-- summaries (not legal text or advice); full-text search ranks them against the inspector's question.
create table rule_passages (
    id      text primary key,
    title   text not null,
    body    text not null,
    tsv     tsvector generated always as (to_tsvector('english', title || ' ' || body)) stored
);

create index rule_passages_tsv on rule_passages using gin (tsv);

insert into rule_passages (id, title, body) values
('R1', 'What Local Law 18 covers',
 'New York City Local Law 18 regulates short-term rentals, meaning stays of fewer than 30 consecutive nights. '
 || 'Enforcement began in September 2023 and is run by the Mayor''s Office of Special Enforcement (OSE).'),
('R2', 'Host registration',
 'A host must register with the Office of Special Enforcement and receive a registration number before hosting '
 || 'short-term guests. The registration number is shown on the listing and starts with OSE-STRREG.'),
('R3', 'Booking platforms',
 'Booking platforms such as Airbnb must check that a short-term listing has a valid registration and may not '
 || 'process bookings or collect fees for short-term stays at unregistered listings.'),
('R4', 'Host present, at most two guests',
 'Under New York City rules a registered short-term rental generally requires the host to live in the home and be '
 || 'present during the stay, with no more than two paying guests. Renting an entire home or apartment for fewer '
 || 'than 30 nights is generally not allowed.'),
('R5', 'Stays of 30 nights or more',
 'Rentals of 30 or more consecutive nights are outside Local Law 18 registration. RentGuard treats a listing whose '
 || 'minimum stay is 30 nights or more as long_term_30_plus.'),
('R6', 'How RentGuard assigns a status',
 'registered: the license field holds an OSE-STRREG registration number. exempt: the license field says EXEMPT. '
 || 'unregistered_short_term: minimum stay under 30 nights with neither a registration number nor an exemption. '
 || 'unknown: the minimum stay is missing. Licenses are compared case-insensitively and blanks count as missing.'),
('R7', 'What the data can and cannot show',
 'Statuses come from public Inside Airbnb scrapes of listing pages. Minimum nights is a listing setting, not a '
 || 'record of actual stays, so unregistered_short_term is a screening flag for inspection, not proof that an '
 || 'illegal booking happened. Host ids are platform ids; the data has no names, phone numbers or addresses.'),
('R8', 'Quarantined scrapes',
 'A scrape with less than half the rows of the last applied scrape is treated as partial and quarantined. '
 || 'Quarantined scrapes are kept for audit but never change listing history or compliance counts.'),
('R9', 'Audit ledger and receipts',
 'Every snapshot decision and every listing status change is written to a hash-chained ledger block signed with '
 || 'the service''s ECDSA key. A receipt with a Merkle proof lets anyone confirm a recorded status was not changed '
 || 'later.');
