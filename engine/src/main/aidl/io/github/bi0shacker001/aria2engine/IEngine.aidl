package io.github.bi0shacker001.aria2engine;

import io.github.bi0shacker001.aria2engine.IEngineEvents;

/**
 * The engine process's typed control (docs/design.md, "Layers"). Only the host app's main process may call it:
 * every method checks the caller. Options travel as parallel key and value arrays, aria2's own option names.
 * A gid is aria2's, 16 hex digits; an "error:<code>" answer carries libaria2's error.
 */
interface IEngine {
    /**
     * Confines this process to [broker] (the host's sandbox broker) and starts the libaria2 session with these
     * global options. "on", or "error: <why>"; once started, a second call answers "error: started".
     */
    String start(in ParcelFileDescriptor broker, in String[] keys, in String[] values, IEngineEvents events);

    String addUri(in String[] uris, in String[] keys, in String[] values);
    String addTorrent(String path, in String[] keys, in String[] values);
    /** The gids, comma-separated. */
    String addMetalink(String path, in String[] keys, in String[] values);

    int pause(String gid, boolean force);
    int unpause(String gid);
    int remove(String gid, boolean force);
    int changeOption(String gid, in String[] keys, in String[] values);
    int changeGlobalOption(in String[] keys, in String[] values);

    /** One download as JSON (EngineNative.status), or null when the session no longer knows it. */
    String status(String gid);
    /** The gids of every active, waiting or paused download, comma-separated. */
    String active();

    /** Ends the session; the process ends with the host's unbind. */
    void stop();
}
