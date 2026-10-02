import { test } from "node:test";
import assert from "node:assert/strict";
import { mergeChannelLists, V14_ONLY_ALLOWLIST } from "../src/jio/channels";

test("v3.1 wins on shared ids", () => {
  const out = mergeChannelLists(new Map([[143, "v31"]]), new Map([[143, "v14"]]));
  assert.equal(out.get(143), "v31");
});

test("v1.4-only ids are kept only when allow-listed", () => {
  const out = mergeChannelLists(
    new Map([[1, "a"]]),
    new Map([[625, "Zee Bangla"], [2001, "PlusTest2 HD"], [3507, "Sony YAY"], [167, "Zee TV HD"]])
  );
  assert.deepEqual([...out.keys()].sort((a, b) => a - b), [1, 625]);
});

test("allowlist is the v1.5.5 Zee regional set", () => {
  assert.deepEqual([...V14_ONLY_ALLOWLIST.keys()].sort((a, b) => a - b), [413, 414, 625, 628, 722, 1691, 3476]);
});
