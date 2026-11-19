## PATCH http://localhost:39873/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Thu, 19 Nov 2026 12:49:04 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:39873/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
