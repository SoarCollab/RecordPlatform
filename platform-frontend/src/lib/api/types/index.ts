import type { components as OpenApiComponents } from "./generated";

// Re-export generated OpenAPI contract accessors.
export type {
  components as OpenApiComponents,
  operations as OpenApiOperations,
  paths as OpenApiPaths,
} from "./generated";

export type OpenApiSchema<Name extends keyof OpenApiComponents["schemas"]> =
  OpenApiComponents["schemas"][Name];

// Re-export all handwritten convenience types.
export * from "./common";
export * from "./auth";
export * from "./files";
export * from "./messages";
export * from "./tickets";
export * from "./system";
export * from "./admin";
export * from "./friends";
