## DELETE http://localhost:38219/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 14 Oct 2026 12:41:23 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:38219/echo' \
  -X DELETE
```
