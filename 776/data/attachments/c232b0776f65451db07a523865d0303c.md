## PATCH http://localhost:44651/echo → 200 OK

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
date: Sat, 03 Oct 2026 14:58:09 GMT
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
curl 'http://localhost:44651/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
