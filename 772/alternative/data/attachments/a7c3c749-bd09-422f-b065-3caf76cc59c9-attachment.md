## DELETE http://localhost:41313/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 03 Oct 2026 07:03:23 GMT
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
curl 'http://localhost:41313/echo' \
  -X DELETE
```
