## PATCH http://localhost:42577/echo → 200 OK

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
date: Mon, 03 Aug 2026 17:07:42 GMT
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
curl 'http://localhost:42577/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
