## POST http://localhost:33727/echo → 200 OK

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
date: Mon, 02 Nov 2026 20:02:32 GMT
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
curl 'http://localhost:33727/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
