package com.xincode.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesProtocolTest {
    @Test
    fun endpointDoesNotDuplicateVersionSegment() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            ResponsesProtocol.endpoint("https://api.openai.com")
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            ResponsesProtocol.endpoint("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://gateway.example/api/v2/responses",
            ResponsesProtocol.endpoint("https://gateway.example/api/v2")
        )
    }

    @Test
    fun requestUsesResponsesInputToolsAndTextFormat() {
        val messages = listOf(
            JSONObject().put("role", "system").put("content", "Be concise."),
            JSONObject().put("role", "user").put("content", "Run the command."),
            JSONObject().apply {
                put("role", "assistant")
                put("content", JSONObject.NULL)
                put("tool_calls", JSONArray().put(JSONObject().apply {
                    put("id", "call_1")
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", "shell_exec")
                        put("arguments", "{\"command\":\"id\"}")
                    })
                }))
            },
            JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", "call_1")
                put("content", "uid=1000")
            }
        )
        val tools = JSONArray().put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "shell_exec")
                put("description", "Execute a shell command.")
                put("strict", true)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().put("command", JSONObject().put("type", "string")))
                    put("required", JSONArray().put("command"))
                    put("additionalProperties", false)
                })
            })
        })
        val responseFormat = JSONObject().apply {
            put("type", "json_schema")
            put("json_schema", JSONObject().apply {
                put("name", "command_result")
                put("schema", JSONObject().put("type", "object"))
                put("strict", true)
            })
        }

        val body = ResponsesProtocol.buildRequest(
            model = "gpt-4o",
            messages = messages,
            tools = tools,
            temperature = 0.2f,
            maxOutputTokens = 512,
            topP = 0.9f,
            responseFormat = responseFormat
        )

        assertFalse(body.has("messages"))
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("response_format"))
        assertEquals("Be concise.", body.getString("instructions"))
        assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals(
            "function_call",
            body.getJSONArray("input").getJSONObject(1).getString("type")
        )
        assertEquals("call_1", body.getJSONArray("input").getJSONObject(1).getString("call_id"))
        assertEquals(
            "function_call_output",
            body.getJSONArray("input").getJSONObject(2).getString("type")
        )
        assertEquals("call_1", body.getJSONArray("input").getJSONObject(2).getString("call_id"))

        val responseTool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", responseTool.getString("type"))
        assertEquals("shell_exec", responseTool.getString("name"))
        assertTrue(responseTool.getBoolean("strict"))

        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("command_result", format.getString("name"))
        assertTrue(format.getBoolean("strict"))
        assertEquals(512, body.getInt("max_output_tokens"))
        assertFalse(body.getBoolean("stream"))
    }

    @Test
    fun responseExtractsOutputTextFunctionCallsAndUsage() {
        val response = JSONObject().apply {
            put("status", "completed")
            put("output_text", "完成")
            put("output", JSONArray().put(JSONObject().apply {
                put("type", "function_call")
                put("id", "fc_1")
                put("call_id", "call_1")
                put("name", "shell_exec")
                put("arguments", "{\"command\":\"id\"}")
            }))
            put("usage", JSONObject().apply {
                put("input_tokens", 11)
                put("output_tokens", 7)
                put("total_tokens", 18)
                put("input_tokens_details", JSONObject().put("cached_tokens", 3))
            })
        }

        val result = ResponsesProtocol.extractResponse(response)

        assertEquals("完成", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls[0].id)
        assertEquals("shell_exec", result.toolCalls[0].name)
        assertEquals("{\"command\":\"id\"}", result.toolCalls[0].arguments)
        assertEquals(11L, result.usage?.getLong("prompt_tokens"))
        assertEquals(7L, result.usage?.getLong("completion_tokens"))
        assertEquals(3, result.usage?.getJSONObject("input_tokens_details")?.getInt("cached_tokens"))
        assertFalse(result.truncated)
    }

    @Test
    fun sseAggregatesTextFunctionArgumentsDoneAndUsage() {
        val parser = ResponsesStreamParser()
        parser.accept("data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}")
        parser.accept("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"shell_exec\",\"arguments\":\"\"}}")
        parser.accept("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{\\\"command\\\":\"}")
        parser.accept("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"id\\\"}\"}")
        parser.accept("data: {\"type\":\"response.function_call_arguments.done\",\"output_index\":0,\"arguments\":\"{\\\"command\\\":\\\"id\\\"}\"}")
        parser.accept("data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"shell_exec\",\"arguments\":\"{\\\"command\\\":\\\"id\\\"}\"}}")
        parser.accept("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":5,\"output_tokens\":2,\"total_tokens\":7}}}")

        val result = parser.result()

        assertEquals("你好", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls[0].id)
        assertEquals("{\"command\":\"id\"}", result.toolCalls[0].arguments)
        assertEquals(5L, result.usage?.getLong("prompt_tokens"))
        assertFalse(result.truncated)
        assertTrue(result.errorMessage == null)
    }

    @Test
    fun incompleteSseIsMarkedTruncated() {
        val parser = ResponsesStreamParser()
        parser.accept("data: {\"type\":\"response.output_text.delta\",\"delta\":\"半截\"}")
        parser.accept("data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":4,\"output_tokens\":1,\"total_tokens\":5}}}")

        val result = parser.result()

        assertEquals("半截", result.content)
        assertTrue(result.truncated)
        assertEquals(4L, result.usage?.getLong("prompt_tokens"))
        assertTrue(result.errorMessage == null)
    }
}
