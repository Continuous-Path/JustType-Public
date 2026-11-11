import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// `vite` serves the demo (index.html); `vite build` emits the library.
export default defineConfig({
	plugins: [react()],
	build: {
		emptyOutDir: false,
		lib: { entry: "src/index.ts", formats: ["es"], fileName: "index" },
		rollupOptions: { external: ["react", "react-dom", "react/jsx-runtime"] },
	},
	test: { include: ["test/**/*.test.{ts,tsx}"] },
});
