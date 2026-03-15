## POST http://localhost:38791/echo → 200 OK

### Request Headers
```
POST http://localhost:38791/echo
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
date: Sun, 15 Mar 2026 11:03:24 GMT
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
curl 'http://localhost:38791/echo' \
  -X POST \
  -d '{"name":"Eve","action":"create"}'
```
