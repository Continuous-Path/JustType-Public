import { useEffect, useMemo, useSyncExternalStore } from "react";
import { type EngineOptions, JustTypeEngine, type Snapshot } from "./engine/engine";

/**
 * Creates a {@link JustTypeEngine} and subscribes to it. The engine is rebuilt only when the
 * language data or store change (the n-gram table and symbol tree are swapped in place); other options are read once at creation
 * (use the engine's setters to change them afterwards).
 */
export function useJustType(options: EngineOptions): { engine: JustTypeEngine; snapshot: Snapshot } {
	const engine = useMemo(() => new JustTypeEngine(options), [options.data, options.store]);
	useEffect(() => engine.setNgrams(options.ngrams), [engine, options.ngrams]);
	useEffect(() => engine.setSymbols(options.symbols), [engine, options.symbols]);
	return { engine, snapshot: useEngineSnapshot(engine) };
}

export function useEngineSnapshot(engine: JustTypeEngine): Snapshot {
	return useSyncExternalStore(engine.subscribe, engine.getSnapshot, engine.getSnapshot);
}

/** Keeps the engine's event handlers pointed at the latest callbacks. */
export function useEngineEvents(engine: JustTypeEngine, events: Parameters<JustTypeEngine["setEvents"]>[0]): void {
	useEffect(() => {
		engine.setEvents(events);
	});
}
