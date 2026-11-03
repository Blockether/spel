## POST http://localhost:37885/echo → 200 OK

### Request Headers
```
Content-Type: application/json
```

### Request Body
```json
{
  "action": "test"
}
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Tue, 03 Nov 2026 17:51:16 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "action": "test"
  }
}
```

### cURL
```bash
curl 'http://localhost:37885/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "action": "test"
}'
```
