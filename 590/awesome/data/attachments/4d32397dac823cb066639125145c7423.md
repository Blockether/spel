## POST http://localhost:37531/echo → 200 OK

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
date: Sun, 12 Apr 2026 18:54:52 GMT
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
curl 'http://localhost:37531/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "action": "test"
}'
```
