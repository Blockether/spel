## POST http://localhost:32821/echo → 200 OK

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
date: Sun, 22 Nov 2026 12:28:40 GMT
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
curl 'http://localhost:32821/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "hello": "world"
}'
```
