## POST http://localhost:38079/echo → 200 OK

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
date: Wed, 23 Sep 2026 11:30:06 GMT
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
curl 'http://localhost:38079/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "hello": "world"
}'
```
