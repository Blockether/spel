## DELETE http://localhost:33237/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 27 Jul 2026 21:19:47 GMT
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
curl 'http://localhost:33237/echo' \
  -X DELETE
```
