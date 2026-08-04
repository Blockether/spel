## POST http://localhost:45909/echo → 200 OK

### Request Headers
```
Content-Type: application/json
```

### Request Body
```json
{
  "hello": "world"
}
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Tue, 04 Aug 2026 22:15:12 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "hello": "world"
  }
}
```

### cURL
```bash
curl 'http://localhost:45909/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "hello": "world"
}'
```
