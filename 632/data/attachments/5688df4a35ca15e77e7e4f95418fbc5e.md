## DELETE http://localhost:35799/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 13 Jul 2026 11:57:09 GMT
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
curl 'http://localhost:35799/echo' \
  -X DELETE
```
