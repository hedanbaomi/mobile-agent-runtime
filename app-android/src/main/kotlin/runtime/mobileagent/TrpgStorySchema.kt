// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/**
 * JSON Schema for one [runtime.mobileagent.domain.trpg.TrpgStoryCommand] (not the
 * expectedRevision wrapper). Every operation is a closed object: only its own fields are allowed,
 * and the required/enum/limit values match the serializable data classes and reducer validation.
 */
internal object TrpgStorySchema {
    const val JSON = """
{
  "type": "object",
  "oneOf": [
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["RENAME_CAMPAIGN"] },
        "campaignName": { "type": "string", "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation", "campaignName"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["UPSERT_CHAPTER"] },
        "chapter": {
          "type": "object",
          "properties": {
            "id": { "type": "string", "minLength": 1, "maxLength": 128 },
            "title": { "type": "string", "minLength": 1, "maxLength": 128 },
            "summary": { "type": "string", "maxLength": 2048 },
            "visibility": { "type": "string", "enum": ["PLAYER", "GAME_MASTER"] }
          },
          "required": ["id", "title"],
          "additionalProperties": false
        }
      },
      "required": ["operation", "chapter"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["REMOVE_CHAPTER"] },
        "id": { "type": "string", "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation", "id"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["SET_ACTIVE_CHAPTER"] },
        "chapterId": { "type": ["string", "null"], "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["UPSERT_SESSION"] },
        "session": {
          "type": "object",
          "properties": {
            "id": { "type": "string", "minLength": 1, "maxLength": 128 },
            "chapterId": { "type": ["string", "null"], "minLength": 1, "maxLength": 128 },
            "title": { "type": "string", "minLength": 1, "maxLength": 128 },
            "recap": { "type": "string", "maxLength": 2048 },
            "visibility": { "type": "string", "enum": ["PLAYER", "GAME_MASTER"] }
          },
          "required": ["id", "title"],
          "additionalProperties": false
        }
      },
      "required": ["operation", "session"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["REMOVE_SESSION"] },
        "id": { "type": "string", "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation", "id"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["SET_ACTIVE_SESSION"] },
        "sessionId": { "type": ["string", "null"], "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["UPSERT_TRACK"] },
        "track": {
          "type": "object",
          "properties": {
            "id": { "type": "string", "minLength": 1, "maxLength": 128 },
            "kind": { "type": "string", "enum": ["QUEST", "CLUE", "CLOCK"] },
            "title": { "type": "string", "minLength": 1, "maxLength": 128 },
            "description": { "type": "string", "maxLength": 2048 },
            "status": { "type": "string", "enum": ["OPEN", "ACTIVE", "DONE", "FAILED"] },
            "progress": { "type": "integer", "minimum": 0, "maximum": 1000 },
            "goal": { "type": "integer", "minimum": 1, "maximum": 1000 },
            "sourceMessageId": { "type": ["string", "null"], "minLength": 1, "maxLength": 256 },
            "visibility": { "type": "string", "enum": ["PLAYER", "GAME_MASTER"] }
          },
          "required": ["id", "kind", "title"],
          "additionalProperties": false
        }
      },
      "required": ["operation", "track"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["REMOVE_TRACK"] },
        "id": { "type": "string", "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation", "id"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["ADVANCE_TRACK"] },
        "id": { "type": "string", "minLength": 1, "maxLength": 128 },
        "delta": { "type": "integer", "minimum": -1000, "maximum": 1000, "not": { "const": 0 } }
      },
      "required": ["operation", "id", "delta"],
      "additionalProperties": false
    }
  ]
}
"""
}
