## PATCH http://localhost:33237/echo → 200 OK

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
date: Mon, 27 Jul 2026 21:19:47 GMT
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
curl 'http://localhost:33237/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
