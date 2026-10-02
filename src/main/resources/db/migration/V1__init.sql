CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats INTEGER NOT NULL CHECK (total_seats > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL CHECK (length(trim(user_id)) > 0),
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('CONFIRMED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ,
    CHECK ((state = 'CONFIRMED' AND cancelled_at IS NULL)
        OR (state = 'CANCELLED' AND cancelled_at IS NOT NULL)),
    UNIQUE (id, show_id)
);
CREATE INDEX reservations_show_user_idx ON reservations(show_id, user_id);

CREATE TABLE show_seats (
    show_id UUID NOT NULL REFERENCES shows(id),
    seat_label VARCHAR(80) NOT NULL CHECK (length(trim(seat_label)) > 0),
    state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (state IN ('AVAILABLE', 'CONFIRMED')),
    reservation_id UUID REFERENCES reservations(id),
    PRIMARY KEY (show_id, seat_label),
    CHECK ((state = 'AVAILABLE' AND reservation_id IS NULL)
        OR (state = 'CONFIRMED' AND reservation_id IS NOT NULL))
);
CREATE INDEX show_seats_reservation_idx ON show_seats(reservation_id);

CREATE TABLE show_user_state (
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL CHECK (length(trim(user_id)) > 0),
    active_seat_count INTEGER NOT NULL DEFAULT 0 CHECK (active_seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);

CREATE TABLE reservation_seats (
    reservation_id UUID NOT NULL,
    show_id UUID NOT NULL,
    seat_label VARCHAR(80) NOT NULL,
    PRIMARY KEY (reservation_id, seat_label),
    FOREIGN KEY (reservation_id, show_id) REFERENCES reservations(id, show_id),
    FOREIGN KEY (show_id, seat_label) REFERENCES show_seats(show_id, seat_label)
);

CREATE TABLE idempotency_requests (
    id UUID PRIMARY KEY,
    user_id VARCHAR(200) NOT NULL CHECK (length(trim(user_id)) > 0),
    idempotency_key VARCHAR(200) NOT NULL CHECK (length(trim(idempotency_key)) > 0),
    show_id UUID NOT NULL REFERENCES shows(id),
    request_fingerprint VARCHAR(64) NOT NULL,
    http_status SMALLINT,
    decline_reason VARCHAR(80),
    reservation_id UUID REFERENCES reservations(id),
    replay_count BIGINT NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE (user_id, idempotency_key),
    CHECK ((http_status IS NULL AND decline_reason IS NULL AND reservation_id IS NULL AND completed_at IS NULL)
        OR (http_status = 201 AND decline_reason IS NULL AND reservation_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (http_status IN (404, 409) AND decline_reason IS NOT NULL
            AND reservation_id IS NULL AND completed_at IS NOT NULL))
);
