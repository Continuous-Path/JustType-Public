import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// `vite` serves the demo (index.html); `vite build` emits the library;
// `vite build --mode demo` emits the demo as a static site.
export default defineConfig(({ mode }) => ({
	plugins: [react()],
	// Relative, so the demo works from any path (GitHub Pages serves it under /<repo>/).
	base: mode === "demo" ? "./" : "/",
	build:
		mode === "demo"
			? { outDir: "demo-dist" }
			: {
					emptyOutDir: false,
					lib: { entry: "src/index.ts", formats: ["es"], fileName: "index", cssFileName: "style" },
					rollupOptions: { external: ["react", "react-dom", "react/jsx-runtime"] },
				},
	test: { include: ["test/**/*.test.{ts,tsx}"] },
}));
