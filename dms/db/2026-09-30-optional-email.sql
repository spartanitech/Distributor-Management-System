-- Run ONCE on the live MySQL database after deploying this update.
-- (spring.jpa.hibernate.ddl-auto=update adds new columns like invoices.round_off
--  automatically, but it never removes a NOT NULL constraint.)

-- 1. Allow users without an email address
ALTER TABLE users MODIFY email VARCHAR(100) NULL;

-- 2. Turn any empty-string emails into NULL so the UNIQUE index
--    doesn't block the 2nd, 3rd... record without an email
UPDATE users           SET email = NULL WHERE TRIM(email) = '';
UPDATE distributors    SET email = NULL WHERE TRIM(email) = '';
UPDATE super_stockists SET email = NULL WHERE TRIM(email) = '';
UPDATE shops           SET email = NULL WHERE TRIM(email) = '';
