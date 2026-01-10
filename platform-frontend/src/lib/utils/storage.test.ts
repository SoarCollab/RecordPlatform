import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  getItem,
  setItem,
  removeItem,
  clearAll,
  theme,
  sidebar,
} from "./storage";

describe("storage utils", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  describe("getItem", () => {
    it("should return default value when key does not exist", () => {
      expect(getItem("nonexistent", "default")).toBe("default");
    });

    it("should return stored value when key exists", () => {
      localStorage.setItem("rp_testKey", JSON.stringify("testValue"));
      expect(getItem("testKey", "default")).toBe("testValue");
    });

    it("should return default value on JSON parse error", () => {
      localStorage.setItem("rp_invalidJson", "not valid json");
      expect(getItem("invalidJson", "default")).toBe("default");
    });

    it("should handle complex objects", () => {
      const obj = { name: "test", count: 42, nested: { value: true } };
      localStorage.setItem("rp_complexObj", JSON.stringify(obj));
      expect(getItem("complexObj", {})).toEqual(obj);
    });

    it("should handle arrays", () => {
      const arr = [1, 2, 3, "four"];
      localStorage.setItem("rp_array", JSON.stringify(arr));
      expect(getItem("array", [])).toEqual(arr);
    });

    it("should handle boolean values", () => {
      localStorage.setItem("rp_bool", JSON.stringify(true));
      expect(getItem("bool", false)).toBe(true);
    });

    it("should handle number values", () => {
      localStorage.setItem("rp_num", JSON.stringify(42));
      expect(getItem("num", 0)).toBe(42);
    });

    it("should handle null stored value", () => {
      localStorage.setItem("rp_nullVal", JSON.stringify(null));
      expect(getItem("nullVal", "default")).toBe(null);
    });
  });

  describe("setItem", () => {
    it("should store string value with prefix", () => {
      setItem("myKey", "myValue");
      expect(localStorage.getItem("rp_myKey")).toBe(JSON.stringify("myValue"));
    });

    it("should store object value", () => {
      const obj = { foo: "bar" };
      setItem("objKey", obj);
      expect(JSON.parse(localStorage.getItem("rp_objKey")!)).toEqual(obj);
    });

    it("should store array value", () => {
      const arr = [1, 2, 3];
      setItem("arrKey", arr);
      expect(JSON.parse(localStorage.getItem("rp_arrKey")!)).toEqual(arr);
    });

    it("should overwrite existing value", () => {
      setItem("overwrite", "first");
      setItem("overwrite", "second");
      expect(getItem("overwrite", "")).toBe("second");
    });

    it("should handle storage errors gracefully", () => {
      const consoleSpy = vi
        .spyOn(console, "error")
        .mockImplementation(() => {});
      const originalSetItem = localStorage.setItem;
      localStorage.setItem = vi.fn(() => {
        throw new Error("QuotaExceededError");
      });

      setItem("errorKey", "value");
      expect(consoleSpy).toHaveBeenCalled();

      localStorage.setItem = originalSetItem;
      consoleSpy.mockRestore();
    });
  });

  describe("removeItem", () => {
    it("should remove item with prefix", () => {
      localStorage.setItem("rp_toRemove", "value");
      removeItem("toRemove");
      expect(localStorage.getItem("rp_toRemove")).toBeNull();
    });

    it("should not throw when removing nonexistent key", () => {
      expect(() => removeItem("nonexistent")).not.toThrow();
    });
  });

  describe("clearAll", () => {
    it("should clear only prefixed items", () => {
      localStorage.setItem("rp_prefixed1", "value1");
      localStorage.setItem("rp_prefixed2", "value2");
      localStorage.setItem("other_key", "other_value");

      clearAll();

      expect(localStorage.getItem("rp_prefixed1")).toBeNull();
      expect(localStorage.getItem("rp_prefixed2")).toBeNull();
      expect(localStorage.getItem("other_key")).toBe("other_value");
    });

    it("should handle empty storage", () => {
      expect(() => clearAll()).not.toThrow();
    });
  });

  describe("theme helper", () => {
    it("should default to 'system'", () => {
      expect(theme.get()).toBe("system");
    });

    it("should get stored theme", () => {
      localStorage.setItem("rp_theme", JSON.stringify("dark"));
      expect(theme.get()).toBe("dark");
    });

    it("should set theme", () => {
      theme.set("light");
      expect(JSON.parse(localStorage.getItem("rp_theme")!)).toBe("light");
    });

    it.each(["light", "dark", "system"] as const)(
      "should accept '%s' theme",
      (themeValue) => {
        theme.set(themeValue);
        expect(theme.get()).toBe(themeValue);
      },
    );
  });

  describe("sidebar helper", () => {
    it("should default to false (not collapsed)", () => {
      expect(sidebar.get()).toBe(false);
    });

    it("should get stored collapsed state", () => {
      localStorage.setItem("rp_sidebar_collapsed", JSON.stringify(true));
      expect(sidebar.get()).toBe(true);
    });

    it("should set collapsed state", () => {
      sidebar.set(true);
      expect(JSON.parse(localStorage.getItem("rp_sidebar_collapsed")!)).toBe(
        true,
      );
    });

    it("should toggle state", () => {
      sidebar.set(false);
      expect(sidebar.get()).toBe(false);

      sidebar.set(true);
      expect(sidebar.get()).toBe(true);
    });
  });
});
