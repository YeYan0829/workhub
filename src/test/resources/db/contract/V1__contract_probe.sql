-- Isolated test fixture only; never included in src/main/resources/db/migration.
CREATE TABLE ${probeTable} (id INT PRIMARY KEY, note VARCHAR(32) NOT NULL) ENGINE=InnoDB;
INSERT INTO ${probeTable} (id,note) VALUES (1,'phase0');
