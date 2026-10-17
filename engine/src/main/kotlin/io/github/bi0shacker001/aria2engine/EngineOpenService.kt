package io.github.bi0shacker001.aria2engine

/**
 * The engine on Android 8 to 10 (docs/design.md, "Processes"): there an isolated process cannot use a socket it was
 * handed, so the engine runs in a process of its own under the host's UID. It is still confined by the sandbox
 * lockdown, with every path it names brokered (the host's broker answers its one folder and the platform's
 * read-only files, nothing else) and every socket the broker's. The owner: "Isolate it as a separate process if we
 * CAN, but if not, sandboxing it ourselves is better than nothing". It becomes the isolated engine when the
 * sandbox's byte relay covers these versions.
 */
class EngineOpenService : EngineService() {
    override val isolated: Boolean = false
}
