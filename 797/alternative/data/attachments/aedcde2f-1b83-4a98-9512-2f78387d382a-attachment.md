## POST http://localhost:46323/echo → 200 OK

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
date: Sat, 24 Oct 2026 08:12:11 GMT
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
curl 'http://localhost:46323/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "hello": "world"
}'
```
