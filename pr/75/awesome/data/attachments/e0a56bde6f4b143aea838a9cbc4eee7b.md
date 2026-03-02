## POST http://localhost:41089/echo → 200 OK

### Request Headers
```
POST http://localhost:41089/echo
```

### Request Body
```json
{
  "name": "Eve",
  "action": "create"
}
```

### Response Headers
```
content-length: 72
content-type: application/json
date: Mon, 02 Mar 2026 14:22:27 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Eve",
    "action": "create"
  }
}
```

### cURL
```bash
curl 'http://localhost:41089/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
