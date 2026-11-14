CREATE TABLE IF NOT EXISTS session_execution_bindings (
    sessionId TEXT NOT NULL PRIMARY KEY,
    executionId TEXT NOT NULL,
    projectRoot TEXT NOT NULL,
    executionRoot TEXT NOT NULL,
    profileId TEXT NOT NULL,
    mode TEXT NOT NULL,
    sshIdentity TEXT,
    isolation TEXT NOT NULL,
    baseCommit TEXT,
    branch TEXT,
    gitRepositoryRoot TEXT,
    gitCommonDir TEXT,
    mountRoot TEXT,
    projectExecutionRoot TEXT NOT NULL,
    state TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    writePaths TEXT
);
CREATE INDEX IF NOT EXISTS index_session_execution_bindings_executionId ON session_execution_bindings(executionId);
ALTER TABLE chat_sessions ADD COLUMN executionBindingRequired INTEGER NOT NULL DEFAULT 0;
ALTER TABLE session_checkpoints ADD COLUMN executionId TEXT;
ALTER TABLE checkpoint_file_snapshots ADD COLUMN resourceKey TEXT;
