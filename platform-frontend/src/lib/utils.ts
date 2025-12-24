import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import type { Snippet } from "svelte";

/**
 * Utility function to merge Tailwind CSS classes
 * Combines clsx for conditional classes and tailwind-merge for conflict resolution
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

// Type utilities for shadcn-svelte components

/**
 * Helper type that adds a ref property to component props
 */
export type WithElementRef<T, E extends HTMLElement = HTMLElement> = T & {
  ref?: E | null;
};

/**
 * Helper type that excludes children and child snippets from props
 */
export type WithoutChildrenOrChild<T> = T extends {
  children?: Snippet;
  child?: Snippet;
}
  ? Omit<T, "children" | "child">
  : T extends { children?: Snippet }
    ? Omit<T, "children">
    : T extends { child?: Snippet }
      ? Omit<T, "child">
      : T;

/**
 * Helper type that excludes the child snippet from props
 */
export type WithoutChild<T> = T extends { child?: Snippet }
  ? Omit<T, "child">
  : T;

/**
 * Helper type that excludes the children snippet from props
 */
export type WithoutChildren<T> = T extends { children?: Snippet }
  ? Omit<T, "children">
  : T;
