## GET http://localhost:43579/echo?user=alice&action=view → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Mon, 02 Nov 2026 20:02:31 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "user=alice&action=view"
}
```

### cURL
```bash
curl 'http://localhost:43579/echo?user=alice&action=view'
```
