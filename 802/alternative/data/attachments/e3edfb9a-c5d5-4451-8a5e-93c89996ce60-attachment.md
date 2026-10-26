## DELETE http://localhost:33949/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 26 Oct 2026 14:21:38 GMT
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
curl 'http://localhost:33949/echo' \
  -X DELETE
```
