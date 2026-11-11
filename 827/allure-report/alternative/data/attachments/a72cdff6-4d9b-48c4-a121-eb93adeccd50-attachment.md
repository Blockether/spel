## PATCH http://localhost:35917/echo → 200 OK

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
date: Wed, 11 Nov 2026 21:05:42 GMT
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
curl 'http://localhost:35917/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
