package io.github.bi0shacker001.aria2engine;

/** libaria2's download events, as its DownloadEvent numbers (EngineEvent), delivered on the host's binder thread. */
oneway interface IEngineEvents {
    void onEvent(int event, String gid);
}
