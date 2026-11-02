## POST http://localhost:34641/echo → 200 OK

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
date: Mon, 02 Nov 2026 20:06:14 GMT
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
curl 'http://localhost:34641/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "hello": "world"
}'
```
