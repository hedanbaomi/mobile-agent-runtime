// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

/**
 * JSON Schema for one [runtime.mobileagent.domain.trpg.TrpgAtlasCommand] (not the
 * expectedRevision wrapper). Every operation is a closed object: only its own fields are allowed,
 * and the required/enum/limit values match the serializable data classes and reducer validation.
 *
 * `x`/`y` are required because an upsert replaces the whole location: an omitted pair would
 * silently move an existing pin to the origin rather than keep it. `SET_CURRENT` accepts an
 * explicit null [locationId] to clear the current marker.
 */
internal object TrpgAtlasSchema {
    const val JSON = """
{
  "type": "object",
  "oneOf": [
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["UPSERT_LOCATION"] },
        "location": {
          "type": "object",
          "properties": {
            "id": { "type": "string", "minLength": 1, "maxLength": 128 },
            "title": { "type": "string", "minLength": 1, "maxLength": 128 },
            "description": { "type": "string", "maxLength": 2048 },
            "x": { "type": "integer", "minimum": 0, "maximum": 1000 },
            "y": { "type": "integer", "minimum": 0, "maximum": 1000 },
            "visibility": { "type": "string", "enum": ["PLAYER", "GAME_MASTER"] }
          },
          "required": ["id", "title", "x", "y"],
          "additionalProperties": false
        }
      },
      "required": ["operation", "location"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["REMOVE_LOCATION"] },
        "id": { "type": "string", "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation", "id"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["UPSERT_EDGE"] },
        "edge": {
          "type": "object",
          "properties": {
            "id": { "type": "string", "minLength": 1, "maxLength": 128 },
            "fromId": { "type": "string", "minLength": 1, "maxLength": 128 },
            "toId": { "type": "string", "minLength": 1, "maxLength": 128 },
            "label": { "type": "string", "maxLength": 128 },
            "visibility": { "type": "string", "enum": ["PLAYER", "GAME_MASTER"] }
          },
          "required": ["id", "fromId", "toId"],
          "additionalProperties": false
        }
      },
      "required": ["operation", "edge"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["REMOVE_EDGE"] },
        "id": { "type": "string", "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation", "id"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "operation": { "type": "string", "enum": ["SET_CURRENT"] },
        "locationId": { "type": ["string", "null"], "minLength": 1, "maxLength": 128 }
      },
      "required": ["operation"],
      "additionalProperties": false
    }
  ]
}
"""
}
